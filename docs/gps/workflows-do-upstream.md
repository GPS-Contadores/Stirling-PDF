# Workflows do upstream no fork da GPS

O fork herda os workflows do Stirling-PDF em `.github/workflows/` (38 na
`v2.14.3`). Sem trava, eles disparam aqui:

- **Todo PR:** `PR-Auto-Deploy-V2.yml`, `PR-Demo-cleanup.yml` e `check_toml.yml`.
  O `check_toml.yml` falha sempre no passo "Setup GitHub App Bot", porque o App
  só existe no upstream, e deixa o PR vermelho sem relação com o código.
- **Push na `main`** (quando ela é sincronizada com o upstream): `ai-engine.yml`,
  `build-enterprise.yml`, `push-docker.yml`, `scorecards.yml`, e
  `sync_files_v2.yml` e `frontend-backend-licenses-update.yml`, que **abrem PR**.
- **Release:** `multiOSReleases.yml`, `aur-publish.yml`, `package-managers.yml`.
- **Agenda:** `stale.yml`, `nightly.yml`, `build-enterprise.yml`,
  `scorecards.yml`, `manage-label.yml`. O GitHub não roda workflow agendado em
  fork por padrão, e não houve nenhum run agendado aqui, mas a trava abaixo
  cobre esses também.

## Regra

Só os workflows `gps-*.yml` rodam. Os do upstream são **travados, não
apagados**: apagar o arquivo gera conflito modify/delete a cada sincronização.

```bash
scripts/gps/desligar-workflows-do-upstream.sh --dry-run   # confere
scripts/gps/desligar-workflows-do-upstream.sh             # aplica
```

## Por que duas travas

A API só desliga workflow que **já rodou uma vez**. O GitHub só registra um
workflow depois do primeiro disparo, e para os que nunca rodaram a chamada
`PUT .../actions/workflows/<arquivo>/disable` responde 404, mesmo pelo nome do
arquivo. Eles também não aparecem no painel nem em `gh workflow list`. Na
primeira execução, 34 dos 38 estavam nessa situação.

1. **Lista de actions permitidas** (Settings → Actions → General). O script
   permite só as actions usadas pelos `gps-*.yml` da `gps/main`. Todo workflow
   do upstream usa `step-security/harden-runner`, então nenhum passa da
   partida: o run termina em `startup_failure` sem executar nenhum passo. Isso
   vale para os que nunca rodaram e para os que chegarem em sincronizações
   futuras.
2. **Desligar pela API** os que já estão registrados, para que não pintem commit
   e PR de vermelho com `startup_failure`.

## Depois de cada sincronização com o upstream

**Rode o script de novo.** Ele é idempotente. Se um workflow do upstream bateu
na trava 1 e ficou registrado, o script o desliga.

## Ao mudar as actions de um `gps-*.yml`

Uma action nova num `gps-*.yml` fica bloqueada pela trava 1 até o script rodar
de novo **depois do merge** na `gps/main`, porque ele lê a lista dos arquivos
dessa branch. Sem isso, o workflow da GPS também termina em `startup_failure`.

Para conferir: `gh workflow list -R GPS-Contadores/Stirling-PDF --all` deve
mostrar ativos só os workflows `GPS - ...`, e
`gh api repos/GPS-Contadores/Stirling-PDF/actions/permissions/selected-actions`
deve listar só as actions dos `gps-*.yml`.
