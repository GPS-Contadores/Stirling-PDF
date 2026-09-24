#!/usr/bin/env bash
# Impede que os workflows do upstream rodem no fork da GPS. Só gps-*.yml roda.
#
# Os workflows do Stirling-PDF chegam a cada sincronização com o upstream e
# disparam aqui: PR-Auto-Deploy-V2.yml roda em todo PR, check_toml.yml falha
# por falta do GitHub App do upstream, e uma sincronização da `main` dispararia
# workflows que abrem PR (sync_files_v2.yml) ou publicam imagem (push-docker.yml).
# Os arquivos ficam no repositório de propósito: apagá-los gera conflito
# modify/delete a cada sincronização.
#
# Duas travas, porque a API só desliga workflow que já rodou uma vez (os que
# nunca rodaram respondem 404, mesmo pelo nome do arquivo):
#
#   1. Lista de actions permitidas = só as que os gps-*.yml usam. Todo workflow
#      do upstream usa step-security/harden-runner, então nenhum passa da
#      partida (startup_failure). Cobre também os que nunca rodaram e os que
#      chegarem em sincronizações futuras, desde que usem alguma action fora
#      da lista: o script confere isso e acusa como DESCOBERTO o que escaparia.
#   2. Desliga pela API os workflows do upstream já registrados, para não
#      pintar commit e PR de vermelho com startup_failure.
#
# Idempotente: rode de novo depois de cada sincronização com o upstream e
# depois de mudar as actions de um gps-*.yml.
#
#   scripts/gps/desligar-workflows-do-upstream.sh            # aplica
#   scripts/gps/desligar-workflows-do-upstream.sh --dry-run  # só mostra
#
# Requer só o gh, autenticado com admin no repositório (a trava 1 é
# configuração). Sai com erro se algo falhar ou se algum workflow escapar.
set -euo pipefail

REPO="${REPO:-GPS-Contadores/Stirling-PDF}"
REF="${REF:-gps/main}"
DRY_RUN=false

uso() {
  echo "uso: $0 [--dry-run]"
}

# Argumento desconhecido é erro: cair no modo que aplica por um "-n" ou um
# "--help" mudaria a configuração do repositório sem querer. Laço com shift em
# vez de `for arg in "$@"`: sob set -u, o bash 3.2 do macOS acusa "$@" vazio.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true ;;
    -h | --help)
      uso
      exit 0
      ;;
    *)
      echo "argumento desconhecido: $1" >&2
      uso >&2
      exit 2
      ;;
  esac
  shift
done

# Conteúdo cru (Accept: raw) em vez do .content em base64: dispensa o base64,
# cuja opção de decodificar muda entre sistemas. Num erro o gh escreve o corpo
# do erro no stdout, por isso quem chama testa o status, nunca a saída.
baixar_workflow() {
  gh api -H 'Accept: application/vnd.github.raw' \
    "repos/$REPO/contents/.github/workflows/$1?ref=$REF"
}

# owner/repo[/caminho] de cada `uses:` externo, com ou sem aspas. Os locais
# (./...) ficam de fora: a lista de actions não os controla.
actions_de() {
  sed -nE "s/^[[:space:]-]*uses:[[:space:]]*[\"']?([^.\"'[:space:]][^@\"'[:space:]]*)@.*/\1/p" <<<"$1"
}

# Verdadeiro se o workflow usa alguma action fora de $permitidas, que é o que o
# faz parar na trava 1. Um workflow só com `run:`, ou só com actions da lista,
# passa por ela.
usa_action_barrada() {
  local action
  while IFS= read -r action; do
    [[ -n "$action" ]] || continue
    grep -Fqx -- "$action" <<<"$permitidas" || return 0
  done <<<"$(actions_de "$1")"
  return 1
}

# Lista pelos arquivos da branch, não pela API de workflows: o GitHub só
# registra um workflow depois do primeiro disparo. Laço em vez de mapfile: o
# bash do macOS é 3.2. A listagem vai para uma variável antes do laço porque a
# falha de um `< <(...)` passa em silêncio pelo set -e.
if ! lista=$(gh api "repos/$REPO/contents/.github/workflows?ref=$REF" \
  --jq '.[] | select(.type == "file") | .name | select(test("\\.ya?ml$"))'); then
  echo "erro: não consegui listar .github/workflows em $REPO@$REF; nada foi alterado" >&2
  exit 1
fi
if [[ -z "$lista" ]]; then
  echo "erro: nenhum workflow em $REPO@$REF; confira REPO e REF" >&2
  exit 1
fi
arquivos=()
while IFS= read -r nome; do
  arquivos+=("$nome")
done <<<"$lista"

# --- Trava 1: actions permitidas ---------------------------------------------

# Uma action por linha. Se a leitura de um gps-*.yml falhar, o script para:
# aplicar a lista sem as actions dele deixaria esse workflow da GPS barrado.
permitidas=""
for arquivo in "${arquivos[@]}"; do
  [[ "$arquivo" == gps-* ]] || continue
  if ! conteudo=$(baixar_workflow "$arquivo"); then
    echo "erro: não consegui ler $arquivo em $REF; nada foi alterado" >&2
    exit 1
  fi
  permitidas+="$(actions_de "$conteudo")"$'\n'
done
permitidas=$(sed '/^$/d' <<<"$permitidas" | sort -u)

SEM_ADMIN="ilegível sem admin"
if atual=$(gh api "repos/$REPO/actions/permissions" --jq '"\(.enabled) \(.allowed_actions)"' 2>/dev/null); then
  habilitado=${atual%% *}
  allowed_antes=${atual#* }
elif $DRY_RUN; then
  # Sem admin a configuração do Actions não é legível; o dry-run segue assim
  # mesmo, porque o resto (listas e estado dos workflows) só pede leitura.
  habilitado="?"
  allowed_antes=$SEM_ADMIN
else
  echo "erro: não consegui ler a configuração do Actions de $REPO (requer admin); nada foi alterado" >&2
  exit 1
fi
if [[ "$habilitado" == false ]]; then
  # O PUT abaixo exige enabled=true. Enviá-lo com o Actions desligado o
  # religaria, e um script que só restringe não deve fazer isso.
  echo "erro: o Actions está desligado em $REPO. O script não o religa; ligue pelo painel e rode de novo." >&2
  exit 1
fi

if [[ -z "$permitidas" ]]; then
  # Sem action nos gps-*.yml, uma lista vazia pararia todo o Actions.
  echo "trava 1: nenhum gps-*.yml com actions em $REF; lista de actions não alterada" >&2
else
  echo "trava 1: actions permitidas = $(tr '\n' ' ' <<<"$permitidas")"
  if $DRY_RUN; then
    echo "trava 1: hoje allowed_actions=$allowed_antes"
  else
    campos=(-F github_owned_allowed=false -F verified_allowed=false)
    while IFS= read -r action; do
      campos+=(-f "patterns_allowed[]=$action@*")
    done <<<"$permitidas"
    gh api -X PUT "repos/$REPO/actions/permissions" -F enabled=true -f allowed_actions=selected --silent
    # A lista só pode ser gravada com allowed_actions=selected. Se ela for
    # recusada, desfaz a troca acima: `selected` com a lista antiga (ou vazia)
    # barraria também os gps-*.yml.
    if ! gh api -X PUT "repos/$REPO/actions/permissions/selected-actions" "${campos[@]}" --silent; then
      if [[ "$allowed_antes" != selected ]]; then
        gh api -X PUT "repos/$REPO/actions/permissions" -F enabled=true \
          -f allowed_actions="$allowed_antes" --silent || true
      fi
      echo "erro: lista de actions recusada; allowed_actions voltou para $allowed_antes" >&2
      exit 1
    fi
    echo "trava 1: aplicada"
  fi
fi
echo

# O rótulo dos não registrados diz a verdade sobre a trava 1: no dry-run ou
# quando ela foi pulada, vale o estado lido antes, que pode não ser `selected`.
if [[ -n "$permitidas" ]] && ! $DRY_RUN; then
  allowed_agora=selected
else
  allowed_agora=$allowed_antes
fi
case "$allowed_agora" in
  selected) sem_registro="barrado pela trava 1" ;;
  "$SEM_ADMIN") sem_registro="trava 1 $SEM_ADMIN" ;;
  *) sem_registro="CONTINUA LIGADO: trava 1 inativa" ;;
esac

# --- Trava 2: desligar os já registrados -------------------------------------

desligados=0 ja_desligados=0 nao_registrados=0 descobertos=0 mantidos=0 falhas=0
for arquivo in "${arquivos[@]}"; do
  if [[ "$arquivo" == gps-* ]]; then
    echo "mantido         $arquivo"
    mantidos=$((mantidos + 1))
    continue
  fi

  # Só o 404 quer dizer "nunca rodou". Qualquer outro erro (auth, rate limit,
  # 5xx) é falha: tratá-lo como 404 esconderia um workflow ativo. O gh escreve
  # o corpo do erro no stdout, por isso o valor é substituído em vez de
  # concatenado.
  if ! estado=$(gh api "repos/$REPO/actions/workflows/$arquivo" --jq .state 2>/dev/null); then
    if [[ "$estado" != *'"status":"404"'* ]]; then
      echo "FALHOU          $arquivo (leitura do estado)" >&2
      falhas=$((falhas + 1))
      continue
    fi
    # Nunca rodou: só a trava 1 o segura, e só se ele usar action fora da lista.
    if [[ -n "$permitidas" ]]; then
      if ! conteudo=$(baixar_workflow "$arquivo"); then
        echo "FALHOU          $arquivo (leitura do arquivo)" >&2
        falhas=$((falhas + 1))
        continue
      fi
      if ! usa_action_barrada "$conteudo"; then
        echo "DESCOBERTO      $arquivo (só usa actions permitidas: a trava 1 não o segura)" >&2
        descobertos=$((descobertos + 1))
        continue
      fi
    fi
    echo "não registrado  $arquivo ($sem_registro)"
    nao_registrados=$((nao_registrados + 1))
    continue
  fi
  if [[ "$estado" == disabled_* ]]; then
    echo "já desligado    $arquivo"
    ja_desligados=$((ja_desligados + 1))
    continue
  fi

  if $DRY_RUN; then
    echo "desligaria      $arquivo ($estado)"
    desligados=$((desligados + 1))
  elif gh api -X PUT "repos/$REPO/actions/workflows/$arquivo/disable" --silent 2>/dev/null; then
    echo "desligado       $arquivo"
    desligados=$((desligados + 1))
  else
    echo "FALHOU          $arquivo ($estado)" >&2
    falhas=$((falhas + 1))
  fi
done

echo
$DRY_RUN && echo "(dry-run: nada foi alterado)"
echo "desligados: $desligados · já desligados: $ja_desligados · não registrados: $nao_registrados · descobertos: $descobertos · mantidos: $mantidos · falhas: $falhas"
[[ $falhas -eq 0 && $descobertos -eq 0 ]]
