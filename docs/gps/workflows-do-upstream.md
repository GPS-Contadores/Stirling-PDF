# Workflows do upstream no fork da GPS

O fork herda os workflows do Stirling-PDF em `.github/workflows/`, e todos
chegam **ligados**. Sem cuidado, eles disparam aqui:

- **Agenda:** `stale.yml` fecha issues e PRs parados; `nightly.yml`,
  `build-enterprise.yml`, `scorecards.yml` e `manage-label.yml` gastam minutos.
- **Todo PR:** `PR-Auto-Deploy-V2.yml`, `PR-Demo-cleanup.yml` e `check_toml.yml`.
  O `check_toml.yml` falha sempre no passo "Setup GitHub App Bot", porque o App
  só existe no upstream, e deixa o PR vermelho sem relação com o código.
- **Release:** `multiOSReleases.yml`, `aur-publish.yml`, `package-managers.yml`.

## Regra

Só os workflows `gps-*.yml` ficam ligados. Os do upstream são **desligados, não
apagados**: apagar o arquivo gera conflito modify/delete a cada sincronização.

```bash
scripts/gps/desligar-workflows-do-upstream.sh --dry-run   # confere
scripts/gps/desligar-workflows-do-upstream.sh             # desliga
```

## Depois de cada sincronização com o upstream

Workflow novo do upstream chega ligado. **Rode o script de novo.** Ele é
idempotente: pula o que já está desligado e mantém os `gps-*.yml`.

Para conferir: `gh workflow list -R GPS-Contadores/Stirling-PDF --all` deve
mostrar ativos só os workflows `GPS - ...`.

## Workflow que nunca rodou

O GitHub só registra um workflow na API depois do primeiro disparo, então
`gh workflow list` não mostra os que nunca rodaram. O script lista pelos
arquivos da `gps/main` e chama a API pelo nome do arquivo
(`PUT .../actions/workflows/stale.yml/disable`). Se a API recusar um arquivo
que nunca rodou, ele sai na linha `FALHOU`: rode o script de novo depois do
primeiro disparo desse workflow ou desligue pelo painel (Actions → workflow →
"Disable workflow").
