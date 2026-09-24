#!/usr/bin/env bash
# Desliga no fork da GPS todo workflow do upstream (tudo que não é gps-*.yml).
#
# Os workflows do Stirling-PDF chegam ligados a cada sincronização com o
# upstream e disparam aqui: stale.yml fecha issues e PRs parados,
# PR-Auto-Deploy-V2.yml roda em todo PR, check_toml.yml falha por falta do
# GitHub App do upstream. Os arquivos ficam no repositório de propósito:
# apagá-los gera conflito modify/delete a cada sincronização.
#
# Idempotente: rode de novo depois de cada sincronização com o upstream.
#
#   scripts/gps/desligar-workflows-do-upstream.sh            # desliga
#   scripts/gps/desligar-workflows-do-upstream.sh --dry-run  # só mostra
#
# Requer gh autenticado com admin ou write no repositório.
set -euo pipefail

REPO="${REPO:-GPS-Contadores/Stirling-PDF}"
REF="${REF:-gps/main}"
DRY_RUN=false
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=true

# Lista pelos arquivos da branch, não pela API de workflows: o GitHub só
# registra um workflow depois do primeiro disparo, e os que nunca rodaram não
# aparecem em `gh workflow list`. Laço em vez de mapfile: o bash do macOS é 3.2.
arquivos=()
while IFS= read -r nome; do
  arquivos+=("$nome")
done < <(
  gh api "repos/$REPO/contents/.github/workflows?ref=$REF" \
    --jq '.[] | select(.type == "file") | .name | select(test("\\.ya?ml$"))'
)

desligados=0 ja_desligados=0 mantidos=0 falhas=0
for arquivo in "${arquivos[@]}"; do
  if [[ "$arquivo" == gps-* ]]; then
    echo "mantido      $arquivo"
    mantidos=$((mantidos + 1))
    continue
  fi

  # 404 = nunca rodou. O gh escreve o corpo do erro no stdout, por isso o valor
  # é substituído em vez de concatenado.
  if ! estado=$(gh api "repos/$REPO/actions/workflows/$arquivo" --jq .state 2>/dev/null); then
    estado="nao-registrado"
  fi
  if [[ "$estado" == disabled_* ]]; then
    echo "já desligado $arquivo"
    ja_desligados=$((ja_desligados + 1))
    continue
  fi

  if $DRY_RUN; then
    echo "desligaria   $arquivo ($estado)"
    desligados=$((desligados + 1))
    continue
  fi

  if gh api -X PUT "repos/$REPO/actions/workflows/$arquivo/disable" --silent 2>/dev/null; then
    echo "desligado    $arquivo"
    desligados=$((desligados + 1))
  else
    echo "FALHOU       $arquivo ($estado)" >&2
    falhas=$((falhas + 1))
  fi
done

echo
$DRY_RUN && echo "(dry-run: nada foi alterado)"
echo "desligados: $desligados · já desligados: $ja_desligados · mantidos: $mantidos · falhas: $falhas"
[[ $falhas -eq 0 ]]
