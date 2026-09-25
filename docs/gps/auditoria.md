# Trilha de auditoria da assinatura digital

Toda chamada à assinatura com certificado (`POST /api/v1/security/cert-sign`)
vira uma linha na trilha: quem assinou, quando, de onde, com qual certificado e
o hash do documento antes e depois. Desenho completo na
[#16](https://github.com/GPS-Contadores/Stirling-PDF/issues/16); esta parte é a
[#18](https://github.com/GPS-Contadores/Stirling-PDF/issues/18).

Fica de fora a finalização de sessão de assinatura (`SigningSessionController`,
em `proprietary/`), que assina pelo `PdfSigningServiceImpl` sem passar pelo
`cert-sign`. Hoje ela não é alcançável: exige login do próprio Stirling, e o
GPS Documentos roda com `enableLogin: false`. Se o login do Stirling for
ligado, esse caminho precisa entrar na trilha antes.

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

- a conexão vem do proxy: o filtro só aceita os headers quando o par da
  conexão TCP é um dos endereços de `GPS_AUDITORIA_PROXYCONFIAVEL` (no Railway,
  `auth-proxy.railway.internal`). Não basta o Stirling não ter domínio público:
  a rede privada do Railway é aberta aos outros serviços do projeto, e o `ofx`,
  que lê PDF de cliente, alcança o Stirling direto;
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

O par sai do socket do Jetty, não de `getRemoteAddr()`. Com
`server.forward-headers-strategy=NATIVE` (padrão do Stirling), o
`getRemoteAddr()` devolve o `X-Forwarded-For`, que quem chama escreve como
quiser. Identidade de outra origem é descartada, vai para o log com o endereço
e, na assinatura, vira linha `negado` com esse endereço no `motivo`.

Um segredo compartilhado injetado pelo proxy (`--basic-auth-password`) não
serve aqui: o oauth2-proxy põe os mesmos headers em todos os upstreams, e o
segredo chegaria também ao `ofx`.

**Assinatura sem `X-Forwarded-Email` é negada** (403) e registrada como
`negado`. No docker-compose local, a porta 8080 pula o proxy: para assinar por
ela, defina `GPS_AUDITORIA_EXIGIRIDENTIDADE=false` (nunca no Railway). Pelo
proxy local, `GPS_AUDITORIA_PROXYCONFIAVEL=auth-proxy` (o nome do serviço no
compose).

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
numa cadeia única que atravessa os meses.

**O que a cadeia acusa:** corrupção acidental e edição, remoção ou troca de
ordem de uma linha no meio do arquivo sem refazer as seguintes. A consulta
mostra o arquivo e a linha.

**O que ela não acusa:** a cadeia não tem chave. Quem tem acesso de escrita ao
volume pode:

- recalcular a cadeia inteira com um script;
- apagar as últimas linhas ou o arquivo do mês mais recente;
- editar a última linha antes de um reinício (a cabeça da cadeia é relida do
  disco quando o serviço sobe).

Uma chave (HMAC) guardada em variável de ambiente pouco mudaria no Railway:
quem escreve no volume é membro do projeto, e membro do projeto lê as
variáveis.

**Âncora fora do volume:** cada gravação escreve no log da aplicação o hash da
linha nova (`Trilha de auditoria: evento … gravado, hash …`), e cada início do
serviço escreve a cabeça que leu do disco. O log do Railway não se edita pelo
volume. Para conferir a trilha, compare o hash de cada linha com o log; uma
cabeça lida no início que não seja o último hash gravado antes dele indica
edição. A retenção do log do Railway é limitada; a âncora definitiva é a cópia
fora da máquina (webhook da #20), além do backup do volume.

**Gravação interrompida:** kill ou disco cheio no meio da gravação deixa uma
linha incompleta. A gravação seguinte começa numa linha nova e se encadeia à
última linha completa. A consulta mostra a incompleta em `integridade.avisos`,
sem dar a cadeia por quebrada. A cadeia só é dada por quebrada quando a linha
seguinte não pula por cima da incompleta.

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

Resposta: `integridade` (`integra`, `eventos`, onde quebrou e `avisos`), `total` e
`eventos` do mais recente para o mais antigo, no mesmo formato do arquivo.

Acesso só para os e-mails em `GPS_AUDITORIA_LEITORES`; os outros recebem 403.
Lista vazia fecha para todos. Os papéis admin/auditor da #19 substituem a lista.

## Variáveis

| Variável | Padrão | Uso |
|---|---|---|
| `GPS_AUDITORIA_LEITORES` | vazio | e-mails que podem consultar, separados por vírgula |
| `GPS_AUDITORIA_PROXYCONFIAVEL` | vazio | nomes ou IPs de onde a identidade do proxy vale, separados por vírgula; no Railway, `auth-proxy.railway.internal`. Vazio nega toda assinatura; `*` aceita qualquer origem (só desenvolvimento) |
| `GPS_AUDITORIA_EXIGIRIDENTIDADE` | `true` | `false` só em desenvolvimento sem proxy |
| `GPS_AUDITORIA_DIRETORIO` | `configs/audit` | outro diretório para a trilha |

Réplica única: a gravação é serializada dentro do processo. Duas réplicas
escrevendo no mesmo volume quebrariam a cadeia. A consulta não trava a
gravação: lê o arquivo, que só cresce, enquanto as assinaturas seguem.
