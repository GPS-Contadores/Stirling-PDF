# Trilha de auditoria da assinatura digital

Toda chamada à assinatura com certificado (`POST /api/v1/security/cert-sign`)
vira uma linha na trilha: quem assinou, quando, de onde, com qual certificado e
o hash do documento antes e depois. Desenho completo na
[#16](https://github.com/GPS-Contadores/Stirling-PDF/issues/16); esta parte é a
[#18](https://github.com/GPS-Contadores/Stirling-PDF/issues/18).

O código fica em `app/core/.../SPDF/gps/auditoria/`, fora de `proprietary/`
(licença) e fora dos arquivos do upstream, para não conflitar na sincronização.
A única mudança em arquivo do upstream é uma condição no `JobExecutorService`
(`app/common`), marcada com `// GPS:`: ele transformava qualquer exceção do job
em 500 "Job failed" e engolia o 403 da negativa. Agora `ResponseStatusException`
chega com o próprio status ao `GlobalExceptionHandler`.

O aspecto casa só por `execution(...)`, sem `args(...)`: o `AutoJobAspect` faz
o `proceed()` numa thread de job, e binding de argumento depende de uma
ThreadLocal que não existe lá (toda assinatura dava 500 "JoinPointMatch was NOT
bound in invocation"). O teste monta a cadeia como o Spring monta para pegar
isso; ver `AuditoriaDaAssinaturaAspectTest.ProceedEmOutraThread`.

## Quem é o usuário

O login é do oauth2-proxy (Entra ID). O `FiltroIdentidadeDoProxy` lê
`X-Forwarded-Email`, `X-Forwarded-User`, `X-Forwarded-Preferred-Username`,
`X-Forwarded-Groups` e `X-Forwarded-For` e põe no MDC, que o `AutoJobAspect`
leva para a thread do job em `?async=true`.

Esses headers só são confiáveis porque:

- o Stirling não tem domínio público no Railway: só o proxy chega nele;
- o oauth2-proxy (v7.15.4, `PASS_USER_HEADERS=true`) **apaga** o valor que o
  cliente mandar nesses headers antes de pôr o do login
  (`PreserveRequestValue: false` em `getPassUserHeaders`, `stripHeaders` em
  `pkg/middleware/headers.go`).

Testado localmente em 25/09/2026 com a imagem `oauth2-proxy:v7.15.4`, com as
mesmas opções de cabeçalho de produção, um emissor OIDC falso e um upstream que
devolve os headers recebidos:

- sem login, a requisição recebe 302 para o login e não chega ao upstream;
- com login de `verdadeiro@…` e `X-Forwarded-Email`, `-User`,
  `-Preferred-Username` e `-Groups` forjados, os quatro chegam com os valores
  do login.

O teste usou sessão por token no `Authorization`; em produção a sessão é por
cookie do Entra. Pelo código, a troca dos headers é o mesmo middleware nos dois
casos; com cookie do Entra não foi testado.

**O IP não é confiável do mesmo jeito.** O proxy mantém o `X-Forwarded-For`
que o cliente mandar e só acrescenta o dele: um `X-Forwarded-For: 6.6.6.6`
forjado chegou como `6.6.6.6, 172.21.0.1`. A trilha grava a cadeia inteira, e
os primeiros itens podem ser inventados. Confiáveis são os itens do fim, postos
pela borda do Railway e pelo proxy. O próprio proxy avisa no log que, sem
`--trusted-proxy-ip`, confia no `X-Forwarded-*` de qualquer origem.

**Assinatura sem `X-Forwarded-Email` é negada** (403) e registrada como
`negado`. No docker-compose local, a porta 8080 pula o proxy: para assinar por
ela, defina `GPS_AUDITORIA_EXIGIRIDENTIDADE=false` (nunca no Railway).

## Onde grava

`$STIRLING_BASE_PATH/configs/audit/assinaturas-AAAA-MM.jsonl` (no Railway, o
volume do `stirling`), um evento JSON por linha, arquivo por mês em UTC.

```json
{"id":"…","quando":"2026-09-24T18:03:11.201Z",
 "usuario":{"email":"fulano@gestao.com.br","usuario":"…","nome_de_usuario":"…","grupos":[],"ip":"200.1.2.3, 10.0.0.2"},
 "ferramenta":"cert-sign/PFX",
 "certificado":{"titular":"GPS CONTADORES LTDA:11222333000181","cpf_cnpj":"11222333000181","serie":"1092",
                "emissor":"AC …","valido_ate":"2027-03-01T12:00:00Z","sha256":"…","arquivo_sha256":"…"},
 "documento":{"nome":"contrato.pdf","sha256_antes":"…","sha256_depois":"…","bytes_depois":48213},
 "resultado":"sucesso","motivo":null,"hash_anterior":"…"}
```

- `resultado`: `sucesso`, `erro` (senha errada, arquivo inválido, PDF sem
  assinatura nova) ou `negado` (sem identidade; na #19, sem permissão).
- O certificado sai da assinatura gravada no PDF. CPF/CNPJ vem do otherName
  ICP-Brasil (`2.16.76.1.3.3` CNPJ, `2.16.76.1.3.1` CPF) ou do fim do CN. Em
  erro, só se sabe o `arquivo_sha256` do certificado enviado.
- **Nunca** entra o conteúdo do documento nem a senha.
- **Sucesso é conferido no PDF de saída**, não no status HTTP: o
  `CertSignController.sign()` engole a exceção e responde 200 com arquivo vazio.
  Nesse caso a linha sai como `erro` com o motivo "o PDF devolvido não traz
  assinatura nova".
- Se a trilha não conseguir gravar (disco cheio, permissão), a assinatura falha.

## Só acréscimo e cadeia de hash

A aplicação não tem caminho que edite ou apague linha. Cada linha leva em
`hash_anterior` o SHA-256 da linha anterior (a primeira de todas, 64 zeros),
numa cadeia única que atravessa os meses. Editar, apagar ou reordenar uma linha
por fora quebra a cadeia, e a consulta mostra onde.

Limite: apagar as últimas linhas do último arquivo não deixa rastro. Quem
resolve isso é a cópia fora da máquina (webhook da #20) e o backup do volume.

**Retenção:** 5 anos (prazo tributário). A aplicação nunca apaga; o expurgo de
arquivos com mais de 5 anos é manual.

## Consulta

`GET /api/v1/gps/auditoria`. Todos os filtros são opcionais:

| Parâmetro | Casa com |
|---|---|
| `usuario` | trecho do e-mail ou do nome de usuário |
| `certificado` | CPF/CNPJ (com ou sem pontuação), série, SHA-256 do certificado ou do arquivo, trecho do titular |
| `documento` | SHA-256 de antes ou de depois, trecho do nome |
| `de`, `ate` | `AAAA-MM-DD`, dias no horário de Brasília, inclusivos |
| `limite` | padrão 500, máximo 5000 |

Resposta: `integridade` (`integra`, `eventos`, e onde quebrou), `total` e
`eventos` do mais recente para o mais antigo, no mesmo formato do arquivo.

Acesso só para os e-mails em `GPS_AUDITORIA_LEITORES`; os outros recebem 403.
Lista vazia fecha para todos. Os papéis admin/auditor da #19 substituem a lista.

## Variáveis

| Variável | Padrão | Uso |
|---|---|---|
| `GPS_AUDITORIA_LEITORES` | vazio | e-mails que podem consultar, separados por vírgula |
| `GPS_AUDITORIA_EXIGIRIDENTIDADE` | `true` | `false` só em desenvolvimento sem proxy |
| `GPS_AUDITORIA_DIRETORIO` | `configs/audit` | outro diretório para a trilha |

Réplica única: a gravação é serializada dentro do processo. Duas réplicas
escrevendo no mesmo volume quebrariam a cadeia.
