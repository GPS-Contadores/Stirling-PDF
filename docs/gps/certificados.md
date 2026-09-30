# Quem pode assinar com cada certificado

Parte 2 da [#16](https://github.com/GPS-Contadores/Stirling-PDF/issues/16):
a [#19](https://github.com/GPS-Contadores/Stirling-PDF/issues/19). Antes de
assinar, o `cert-sign` confere se quem está logado pode usar o certificado
enviado. Se não pode, a assinatura é **negada** (403), não chega a acontecer, e
a trilha de auditoria ([auditoria.md](auditoria.md)) grava a linha `negado` com
os dados do certificado.

## Papéis

Vêm dos **App Roles do Entra** que o oauth2-proxy repassa em
`X-Forwarded-Groups`. O header só vale vindo do proxy
(`GPS_AUDITORIA_PROXYCONFIAVEL`).

| Papel | App Role (padrão) | O que libera |
|---|---|---|
| `admin` | `Documentos.Admin` | tudo o que `auditor` e `assinante` liberam |
| `auditor` | `Documentos.Auditor` | consultar a trilha (`GET /api/v1/gps/auditoria`) |
| `assinante` | `Documentos.Assinante` | assinar com os certificados que listam `papel:assinante` |
| `usuario` | — (todo mundo logado) | o resto do GPS Documentos |

Enquanto os App Roles não existem, os e-mails de `GPS_AUDITORIA_LEITORES`
continuam contando como `auditor`.

**No Entra** (app registration do GPS Documentos): criar os três App Roles
com esses valores, atribuir a pessoas ou grupos pela Enterprise App, e no
oauth2-proxy definir `OAUTH2_PROXY_OIDC_GROUPS_CLAIM=roles`. Assim a claim
`roles` chega em `X-Forwarded-Groups`. App Roles, e não grupos: grupos no token
estouram o cookie de 4 KB do proxy. Ainda falta confirmar que o provider
`entra-id` do oauth2-proxy respeita o `OIDC_GROUPS_CLAIM`; o documento do
conversor-documentos (`docs/entra-id.md`) registra o resultado.

## Lista de certificados

`$STIRLING_BASE_PATH/configs/gps/certificados.yml`, no volume do `stirling`.
É relida quando muda, sem reiniciar.

```yaml
padrao: negar            # certificado fora da lista: negar | permitir
certificados:
  - descricao: GPS Contadores matriz
    cpf_cnpj: "11.222.333/0001-81"     # entre aspas
    permitidos:
      - fulano@gestao.com.br
      - "papel:assinante"
  - descricao: e-CPF do Beltrano
    sha256: "3f1c…"                     # SHA-256 do certificado, entre aspas
    permitidos: [beltrano@gestao.com.br]
```

- Uma entrada casa quando **todas** as chaves que ela traz (`cpf_cnpj`,
  `sha256`) batem com o certificado. O `cpf_cnpj` é o da ICP-Brasil (otherName
  ou fim do CN), comparado só pelos dígitos. O `sha256` é o do certificado,
  o mesmo que a trilha grava em `certificado.sha256`.
- Basta uma entrada que case permitir a pessoa, por e-mail (sem diferença de
  maiúsculas) ou por `papel:<nome>`.
- **Certificado fora da lista** ou que não se consegue ler (tipo `SERVER`,
  `WINDOWS_STORE`, `PKCS11`, arquivo ilegível) segue o `padrao`.
- **Senha errada** não é negativa: a assinatura falha do mesmo jeito, e a trilha
  grava `erro` com o motivo real. A #20 conta essas falhas.
- **Arquivo que não se entende** (YAML inválido, `padrao` diferente de
  `negar`/`permitir`, entrada sem `cpf_cnpj` nem `sha256`, CNPJ ou hash sem
  aspas) **nega tudo**, mesmo com `padrao: permitir`. O motivo aparece na linha
  `negado` e no log. Sem aspas, o YAML lê `11222333000181` como número (e um
  hash como `12e45…` também); por isso `cpf_cnpj` e `sha256` só são aceitos
  como texto.
- **Sem arquivo**, vale `GPS_CERTIFICADOS_PADRAO` (`negar` se vazio), e o log
  avisa na subida.

Para descobrir o `sha256` ou o `cpf_cnpj` de um certificado, assine uma vez com
ele (com `padrao: permitir`, ou pela linha `negado`) e leia a trilha.

Depois de assinar, a trilha confere se o PDF saiu assinado com o certificado
que foi autorizado. Se não saiu, a assinatura não é entregue (403) e a linha
sai `erro` com "o PDF foi assinado com outro certificado, não o autorizado".

## Deploy

**Antes** de subir a imagem com a #19, uma das duas coisas:

1. criar o `certificados.yml` no volume, por exemplo
   `railway ssh -s stirling` e depois
   `cat > /stirling-data/configs/gps/certificados.yml`; ou
2. definir `GPS_CERTIFICADOS_PADRAO=permitir` no serviço `stirling`, para
   manter o comportamento de hoje até a lista existir.

Sem nenhum dos dois, toda assinatura com certificado é negada. É proposital:
sem certeza, nega e registra.

## Variáveis

| Variável | Padrão | Uso |
|---|---|---|
| `GPS_CERTIFICADOS_ARQUIVO` | `configs/gps/certificados.yml` | outro caminho para a lista |
| `GPS_CERTIFICADOS_PADRAO` | `negar` | vale só enquanto o arquivo não existe |
| `GPS_PAPEIS_GRUPOADMIN` | `Documentos.Admin` | valor do App Role de admin |
| `GPS_PAPEIS_GRUPOAUDITOR` | `Documentos.Auditor` | valor do App Role de auditor |
| `GPS_PAPEIS_GRUPOASSINANTE` | `Documentos.Assinante` | valor do App Role de assinante |
| `GPS_AUDITORIA_LEITORES` | vazio | e-mails que contam como auditor até os App Roles existirem |

No docker-compose local, sem proxy (porta 8080), use também
`GPS_CERTIFICADOS_PADRAO=permitir`: sem identidade não há e-mail nem papel para
a lista liberar.
