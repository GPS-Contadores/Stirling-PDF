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
#      chegarem em sincronizações futuras.
#   2. Desliga pela API os workflows do upstream já registrados, para não
#      pintar commit e PR de vermelho com startup_failure.
#
# Idempotente: rode de novo depois de cada sincronização com o upstream e
# depois de mudar as actions de um gps-*.yml.
#
#   scripts/gps/desligar-workflows-do-upstream.sh            # aplica
#   scripts/gps/desligar-workflows-do-upstream.sh --dry-run  # só mostra
#
# Requer gh autenticado com admin no repositório (a trava 1 é configuração).
set -euo pipefail

REPO="${REPO:-GPS-Contadores/Stirling-PDF}"
REF="${REF:-gps/main}"
DRY_RUN=false
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=true

# Lista pelos arquivos da branch, não pela API de workflows: o GitHub só
# registra um workflow depois do primeiro disparo. Laço em vez de mapfile: o
# bash do macOS é 3.2.
arquivos=()
while IFS= read -r nome; do
  arquivos+=("$nome")
done < <(
  gh api "repos/$REPO/contents/.github/workflows?ref=$REF" \
    --jq '.[] | select(.type == "file") | .name | select(test("\\.ya?ml$"))'
)

# --- Trava 1: actions permitidas ---------------------------------------------

padroes=()
for arquivo in "${arquivos[@]}"; do
  [[ "$arquivo" == gps-* ]] || continue
  while IFS= read -r action; do
    padroes+=("$action@*")
  done < <(
    gh api "repos/$REPO/contents/.github/workflows/$arquivo?ref=$REF" --jq .content |
      base64 -d |
      sed -nE 's/^[[:space:]-]*uses:[[:space:]]*([^.[:space:]][^@[:space:]]*)@.*/\1/p'
  )
done

if [[ ${#padroes[@]} -eq 0 ]]; then
  # Sem gps-*.yml na branch, uma lista vazia pararia todo o Actions.
  echo "trava 1: nenhum gps-*.yml em $REF; lista de actions não alterada" >&2
else
  json=$(printf '%s\n' "${padroes[@]}" | sort -u |
    jq -R . | jq -sc '{github_owned_allowed: false, verified_allowed: false, patterns_allowed: .}')
  echo "trava 1: actions permitidas = $(jq -r '.patterns_allowed | join(" ")' <<<"$json")"
  if $DRY_RUN; then
    echo "trava 1: hoje allowed_actions=$(gh api "repos/$REPO/actions/permissions" --jq .allowed_actions)"
  else
    gh api -X PUT "repos/$REPO/actions/permissions" -F enabled=true -f allowed_actions=selected --silent
    gh api -X PUT "repos/$REPO/actions/permissions/selected-actions" --input - --silent <<<"$json"
    echo "trava 1: aplicada"
  fi
fi
echo

# O rótulo dos não registrados diz a verdade sobre a trava 1, lida da API (no
# dry-run ou quando ela foi pulada, pode não estar ativa).
if [[ "$(gh api "repos/$REPO/actions/permissions" --jq .allowed_actions)" == selected ]]; then
  sem_registro="barrado pela trava 1"
else
  sem_registro="CONTINUA LIGADO: trava 1 inativa"
fi

# --- Trava 2: desligar os já registrados -------------------------------------

desligados=0 ja_desligados=0 nao_registrados=0 mantidos=0 falhas=0
for arquivo in "${arquivos[@]}"; do
  if [[ "$arquivo" == gps-* ]]; then
    echo "mantido         $arquivo"
    mantidos=$((mantidos + 1))
    continue
  fi

  # 404 = nunca rodou, e a API não desliga. O gh escreve o corpo do erro no
  # stdout, por isso o valor é substituído em vez de concatenado.
  if ! estado=$(gh api "repos/$REPO/actions/workflows/$arquivo" --jq .state 2>/dev/null); then
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
echo "desligados: $desligados · já desligados: $ja_desligados · não registrados: $nao_registrados · mantidos: $mantidos · falhas: $falhas"
[[ $falhas -eq 0 ]]
