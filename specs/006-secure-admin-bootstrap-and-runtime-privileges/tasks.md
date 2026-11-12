# Tarefas — Iniciativa 006

**Status da iniciativa:** em andamento; T-03 concluída com evidências em 2026-10-26.  
**Autorização vigente:** T-03 e T-04 foram autorizadas e concluídas; T-05.1 e T-05.2 foram autorizadas e concluídas; T-05.3-A, T-05.3-B, T-05.3-C e T-05.3-D foram autorizadas somente para análise, auditoria e planejamento documental. T-05.3 continua incompleta; D-06 a D-10 aguardam aprovação explícita antes de qualquer implementação. T-05.4 em diante e T-06 permanecem sem autorização.

As decisões D-01 a D-05 e DP-01 a DP-06 estão aprovadas. Pendências técnicas e de validação listadas nas tarefas não reabrem esses contratos.
O tratamento de JWT emitido antes do rebaixamento persistido de ADMIN permanece fora do escopo; não deve ser incluído em nenhuma tarefa desta iniciativa.

## T-01 — Caracterizar o estado atual e fechar contratos técnicos

**Estado:** análise documental concluída; sem implementação ou validação de infraestrutura.  
**Objetivo:** caracterizar caminhos e comportamentos concretos para implementar as decisões aprovadas sem alterar seus contratos.  
**Decisões relacionadas:** D-01–D-05, DP-01–DP-06.  
**Escopo:** confirmar comportamento atual de bootstrap/configuração/Flyway; definir contrato de saída do comando e normalização do e-mail; verificar entrada interativa sem eco e limitações de operação automatizada; comparar executor Flyway compatível; investigar grants e `pgcrypto`; identificar inventário e recuperação de bancos existentes; especificar estratégia de prova UID/GID. Somente análise e atualização documental; sem alteração de código/infraestrutura.

**Critérios de conclusão:** caminhos reais e dependências confirmados; contratos de comando (criado/no-op/conflito/falha e códigos de saída) registrados; normalização do e-mail e ausência de configuração bootstrap obrigatória no startup documentadas; escrita runtime em `users` compatível com cadastro USER e proteção PostgreSQL contra ADMIN explicitada; opções técnicas comparadas sem contradizer decisões aprovadas; plano de roles, Testcontainers e transição tem sequência verificável; nenhum critério CA fica com ambiguidade contratual.

**Evidências esperadas:** referências a arquivos/versões reais; matriz de responsabilidades por processo/role; proposta de executor e permissões com evidência PostgreSQL oficial/testável; plano de rollback operacional com limites claramente descritos.

**Dependências:** concluída por análise documental autorizada anteriormente. Implementação segue dependendo de autorização por tarefa e aprovação do plano revisado. Não requer aprovação nova para DP-01–DP-06.

## T-02 — Implementar o comando de bootstrap explícito e idempotente

**Estado:** implementação existente; validação integral e encerramento formal ainda pendentes.  
**Objetivo:** permitir provisionar o primeiro ADMIN com segurança operacional, sem endpoint HTTP ou seed de startup.  
**Decisões relacionadas:** D-01, D-02, D-03, DP-01, DP-02 e DP-05.  
**Escopo:** comando one-shot; senha digitada em terminal interativo, sem eco via stdin; role bootstrap exclusiva; validação/encoder existentes; transação, concorrência e respostas operacionais definidas. Nenhum secret bootstrap na API normal. Execução automatizada do bootstrap fica fora até uma decisão operacional específica.

**Critérios de conclusão:** sem ADMIN criado no startup; primeiro ADMIN criado por invocação explícita; mesmo ADMIN/e-mail retorna no-op e preserva hash; USER/e-mail igual retorna conflito sem promoção; outro ADMIN existente recusa criação sem alteração; corrida não cria duplicata; rollback integral em falha; nenhuma senha aparece em argumentos, saída ou logs.

**Testes/evidências esperadas:** testes de entrada/validação interativa; PostgreSQL com conexões independentes para criação, no-op, conflitos e concorrência; verificação de hash imutável e rollback; teste de startup sem seed; inspeção de saída/logs sem senha. Teste unitário isolado não comprova role operacional nem concorrência.

**Dependências:** T-01; T-03 deve preceder a integração do comando para fornecer role/credencial bootstrap separada. Qualquer desenvolvimento isolado ainda requer autorização para T-02.

## T-03 — Separar roles e validar migrations/privilégios PostgreSQL

**Estado:** concluída em 2026-10-26.  
**Objetivo:** garantir migrations fora do processo normal da API e comprovar privilégios mínimos das identidades no PostgreSQL.  
**Decisões relacionadas:** D-04, DP-03 e DP-05.  
**Escopo:** separar identidades de provisionamento, migrator, runtime e bootstrap; executar Flyway em job/processo one-shot; determinar grants mínimos; analisar V1–V5, `pgcrypto`, funções, triggers, tabelas, constraints e ownership; proteger `users` no PostgreSQL para permitir cadastro `USER` e impedir criação/promoção de `ADMIN`; manter os fluxos normais de vínculo Customer e auditoria; provar privilégios efetivos com conexões distintas em PostgreSQL 16 descartável e adaptar Testcontainers. Configurar a separação das credenciais runtime e migration. A compatibilidade com bancos existentes e sua transição pertencem à T-05, não são comprovadas por T-03. Não editar migrations aplicadas.

**Critérios de conclusão:** V1–V5 funcionam com migrator não superusuário em PostgreSQL 16 descartável, com pré-requisitos de `pgcrypto` explícitos; API inicia com runtime e `ddl-auto: validate`, sem configuração bootstrap obrigatória, e não recebe secrets de migration/bootstrap; runtime consegue registrar USER e executar os fluxos suportados, inclusive Customer-link e INSERT de auditoria, mas PostgreSQL rejeita INSERT de ADMIN, promoção de USER para ADMIN e tentativa runtime de alterar/desativar a proteção; runtime não consegue instalar extensões, executar DDL/CREATE ROLE/DB/DELETE/TRUNCATE nem modificar/remover auditoria; bootstrap role é exclusiva e não alcançável pela API; falhas do job são identificáveis e bloqueiam o rollout sem fallback a superuser. Esta tarefa não declara compatibilidade nem executa transição de bancos existentes.

**Testes/evidências esperadas:** PostgreSQL 16 Testcontainers com setup, migrator, runtime e bootstrap em identidades distintas; banco descartável vazio; migração por Flyway role não superusuária e prova dos privilégios/pré-requisitos de `pgcrypto`; API com runtime e `ddl-auto: validate` sem variáveis bootstrap; comprovar INSERT de USER permitido, INSERT de ADMIN e promoção para ADMIN negados, proteção não alterável/desativável pelo runtime, operações Customer-link/auditoria permitidas e mutação/remoção de auditoria negada; provar privilégios DDL/roles e falhas do job. Sem fixture de banco existente nesta tarefa. Adaptar testes com `@ServiceConnection` sem reduzir cobertura.

**Dependências:** T-01. Seleção técnica de executor e grants ocorre após prova. T-02 integrado depende de T-03 concluída.

**Evidências registradas:**
- `PostgresRolePrivilegesIntegrationTest` aplicou V1–V5 em PostgreSQL 16 descartável com `app_migrator`; as cinco versões foram confirmadas e o papel não tem `SUPERUSER`, `CREATEDB` ou `CREATEROLE`. `pgcrypto` foi provisionada por `provisioner` antes do Flyway e permaneceu pertencendo a essa identidade.
- Conexões reais como `app_runtime` confirmaram cadastro USER e negaram INSERT/PROMOÇÃO ADMIN, `CREATE EXTENSION hstore`, DDL, CREATE ROLE/DB, alteração/desativação do trigger, mudança de `session_replication_role`, acesso às roles privilegiadas, DELETE e TRUNCATE nas tabelas protegidas e mutação/remoção de auditoria.
- Contexto Spring com runtime, Flyway desabilitado e `ddl-auto: validate` iniciou sem criar ADMIN. O fluxo de cadastro via `AuthService` e atribuição/remoção via `UserCustomerLinkService` gravou USER e os eventos `CREATE`/`REMOVE` em auditoria usando as permissões runtime. A conexão bootstrap foi usada separadamente para inserir ADMIN.
- Tentativa de Flyway como runtime falhou. Em Compose, o job válido aplicou V1–V5 e terminou com código `0`; o job com senha descartável inválida terminou com código `1`, e um serviço probe dependente não iniciou (`service_completed_successfully`).
- Smoke test operacional usou projeto Compose isolado, arquivo de configuração e credenciais descartáveis, portas alternativas e volume novo rotulado para esse projeto. API iniciou com `DB_USERNAME=app_runtime`; inspeção do ambiente efetivo da API não encontrou variáveis de bootstrap ou migration. Cadastro HTTP foi persistido como USER e `--profile bootstrap run --rm bootstrap` criou ADMIN. O volume e containers de teste foram removidos; volumes preexistentes não foram alterados.
- Verificações Maven: `mvn clean test` — 180 testes, 0 falhas, 0 erros, 0 ignorados; `mvn -DskipTests package` — BUILD SUCCESS. O `docker compose config --services` também foi validado. Nenhuma migration existente foi alterada.

**Limites:** não foi executada transição de banco existente (T-05), nem validação de imagem nonroot (T-04) ou encerramento integrado T-06. Esta conclusão vale somente para os critérios da T-03 em banco descartável e configuração Compose validada; não comprova compatibilidade de bancos existentes.

## T-04 — Executar a imagem como usuário não privilegiado

**Estado:** concluída em 2026-10-28, após build e verificações reais de imagem e container.  
**Objetivo:** executar a aplicação em imagem final com UID/GID numéricos estáveis não root.  
**Decisões relacionadas:** D-05, DP-06.  
**Escopo:** permissões mínimas para JAR, certificados, configuração e diretórios temporários; compatibilidade JVM, Postgres e health checks. Não alterar privilégios de aplicação fora da iniciativa.

**Critérios de conclusão:** execução observada com UID/GID configurados diferentes de zero; JVM/API inicia e conecta ao Postgres com runtime role; health checks funcionam; artefatos são legíveis; escrita só ocorre em diretórios requeridos; temporários funcionam; diretórios desnecessários não são graváveis.

**Testes/evidências esperadas:** build e execução da imagem; captura de UID/GID efetivos; smoke check de startup/health e conexão; verificação de leitura de truststore e diretório temporário. Presença de `USER` isoladamente não satisfaz a tarefa.

**Implementação e evidências desta execução:**
- Dockerfile final declara `USER 10001:10001`; JAR é copiado como root, modo `0444`, e `/app` permanece sem escrita para o usuário de runtime. Não foram alterados `docker-compose.yml`, as credenciais ou migrations V1–V5.
- `scripts/Test-ApiNonRootImage.ps1` foi corrigido para passar comandos em argumentos separados ao `sh -c`, sem substituições `$(...)` interpretadas pelo PowerShell. Cada verificação falha com nome claro. O script também valida configuração Compose, executa migração/API em PostgreSQL descartável com recursos identificáveis e limpa containers, rede, volume anônimo e arquivo temporário próprios.
- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-ApiNonRootImage.ps1`: passou. Build da imagem `java-modernization-platform:t04`; configuração Compose validada com credenciais efêmeras; UID/GID efetivos do container 10001/10001; JAR e truststore legíveis; escrita em `/tmp` confirmada; `/`, `/app`, `/opt/java/openjdk` e `/etc/ssl/certs` não graváveis; `java -version` executado com sucesso.
- Smoke real em recursos descartáveis: PostgreSQL 16 ficou pronto e recebeu roles separadas; job Flyway executou com `app_migrator` e terminou com sucesso; API iniciou como `app_runtime`, sem variáveis de migration/bootstrap; `/proc/1/status` mostrou processo Java PID 1 com UID/GID efetivos 10001/10001; `/actuator/health` retornou `UP`, incluindo indicador de datasource/PostgreSQL. Registro HTTP de usuário retornou `201`, e a linha persistida no PostgreSQL ficou com papel `USER`.
- Falhas intermediárias e correções: o log inicial fornecido pelo usuário reportou `test $(id: 2: Syntax error: end of file unexpected (expecting ")")`; o quoting foi removido separando as chamadas `sh -c`. Em execução posterior, a espera prematura recebeu `FATAL: the database system is shutting down`; a tentativa seguinte registrou `psql: error: connection to server on socket ... failed: No such file or directory`. A prontidão agora exige `SELECT 1` bem-sucedido com tentativas limitadas. Também foi corrigida a limpeza que antes mascarava a falha original. A execução final passou integralmente.
- Inventário Docker antes/depois confirmou preservação dos containers e volumes nomeados preexistentes. Dois volumes anônimos criados pelas execuções descartáveis anteriores foram identificados pela data/rótulo e removidos; na verificação final não restaram containers, redes ou volumes T-04.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean test`: BUILD SUCCESS; 180 testes, 0 falhas, 0 erros, 0 ignorados.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" -DskipTests package`: BUILD SUCCESS.

**Limitações e estado:** nesta execução, `docker compose config --quiet` foi validado com env file efêmero e o smoke de execução de imagem/API/migration foi feito em containers descartáveis; o fluxo Compose completo previamente exercitado em T-03 está documentado na seção T-03. A configuração Compose não foi alterada. T-04 está concluída pelos critérios de imagem, identidade efetiva, permissões, operação runtime, startup, health, conexão PostgreSQL e separação de credenciais. T-05 e T-06 permanecem pendentes.

**Dependências:** T-01 para mapear filesystem e requisitos; operação pode ser desenvolvida após autorização sem aguardar T-02/T-03, mas integração final é T-06.

## T-05 — Transição segura de bancos existentes e documentação operacional

**Estado:** em implementação por subtarefas; T-05.1 concluída documentalmente e T-05.2 implementada; T-05.3 permanece incompleta após análise de viabilidade. T-05 não está concluída.  
**Objetivo:** migrar com segurança bancos existentes para os papéis e privilégios estabelecidos em T-03, preservando dados, e documentar a transição.  
**Decisões relacionadas:** D-04, DP-03–DP-05.  
**Escopo:** identificar e validar estados iniciais suportados; usar fixture, clone ou ambiente equivalente; inventariar schema, ownership, extensões, roles/grants, dados e histórico Flyway; aplicar procedimento versionado, idempotente e forward-only, sem reescrever migrations aplicadas; preservar dados e integridade referencial; verificar objetos, privilégios e estado Flyway após a transição; documentar pré-condições, ordem, verificações, falhas e recuperação, sem rollback automático ou fallback a superuser. Pode atualizar documentação operacional relacionada sem antecipar T-02. Não acessar `.env` real.

**Critérios de conclusão:** procedimento de transição foi exercitado em fixture/clone representativo; dados, integridade referencial e histórico Flyway são preservados; ownership, objetos e privilégios pós-transição são verificados contra os resultados de T-03; reexecução e falhas têm comportamento documentado e não destrutivo; recuperação, pré-condições e limites estão descritos; não há edição de migrations aplicadas nem declaração de transição para estados não testados.

**Testes/evidências esperadas:** comparação de inventário e dados antes/depois em clone/fixture; verificações de integridade referencial, ownership relevante, grants, objetos e histórico Flyway; teste de aplicação e reexecução do procedimento nos estados suportados; simulação segura/documentada das falhas recuperáveis; runbook com comandos, pré-condições, validação e recuperação. Não usar banco com dados do usuário.

**Dependências:** T-01 para critérios de estado inicial; T-03 para roles, grants e mecanismos de proteção; T-02 para integração bootstrap pertinente à configuração final; T-04 para configuração da imagem. Sem essas definições, não iniciar a transição. T-06 valida o resultado integrado.

### T-05.1 — Definir estados suportados para transição

**Estado:** concluída documentalmente em 2026-11-05; sem implementação.  
**Escopo autorizado:** classificar os estados iniciais, registrar critérios objetivos, contrato do inventário, condições de aborto e evidências exigidas.  
**Matriz:** consultar `plan.md`, seção 3.3, subseção “T-05.1 — Estados iniciais aceitos e classificação”. Estados suportados condicionalmente: V1–V4 íntegros com um owner legado único e conhecido; V1–V5 íntegros com um owner legado único e conhecido. Provisionamento parcial de roles, histórico ausente/inconsistente, ownership misto e objetos/dependências adicionais permanecem não suportados e exigem diagnóstico manual.  
**Critério de conclusão:** matriz fechada e fail-closed, inventário sem segredos, abortos e evidências documentados; nenhuma transição, script, fixture, migration ou configuração alterada.  
**Evidência desta execução:** atualização documental de `plan.md` e `tasks.md`. Não houve acesso a banco, alteração de infraestrutura ou execução de testes.

### T-05.2 — Implementar inventário PostgreSQL somente leitura

**Estado:** concluída em 2026-11-05.  
**Escopo:** comando `--inventory-readonly`, relatório JSON, classificação de candidato técnico, testes Testcontainers PostgreSQL 16 e documentação de uso. Não executa transição.  
**Implementação:** `DatabaseInventoryCommand` lê credenciais somente das variáveis `INVENTORY_DB_URL`, `INVENTORY_DB_USERNAME` e `INVENTORY_DB_PASSWORD`; usa uma transação `REPEATABLE READ READ ONLY` com rollback e Flyway `info()` por URL JDBC `readOnly=true`. Consulta checksums instalados e compara com checksums dos recursos V1–V5; não chama `migrate`, `repair`, `baseline` ou `clean`. A verificação registra PostgreSQL, Flyway, roles/memberships/atributos, grants efetivos, schemas/owners/ACLs, objetos/dependências, extensões, definições e estado de V4/V5, constraints e contagens sem copiar linhas pessoais.  
**Classificação:** apenas os dois estados da T-05.1 resultam em `SUPPORTED_V1_V4`/`SUPPORTED_V1_V5` candidato. Histórico ausente, falha e divergência são `REJECTED`; ausência de evidência suficiente resulta em `UNKNOWN` ou bloqueador. Roles incompletas, objetos extras, ownership misto e proteções divergentes são recusados. O estado V1–V5 exige o conjunto completo de roles alvo com atributos/memberships aprovados.  
**Limites:** consumidores, API antiga, parada de tráfego e restauração do backup não são verificáveis somente pelos catálogos. O relatório os marca como não verificados e não interpreta a referência opcional de backup como prova. A classificação técnica não autoriza transição. A identidade de conexão precisa ter leitura suficiente; ausência de dados necessários bloqueia uma classificação positiva.  
**Testes/evidências:** `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" -Dtest=DatabaseInventoryCommandIntegrationTest test` — BUILD SUCCESS; 11 testes, 0 falhas, 0 erros, 0 ignorados. Os cenários cobrem V1–V4, V1–V5, histórico ausente, checksum divergente, migration malsucedida, ownership misto, roles parciais, objeto extra, trigger desabilitado, função divergente e repetição. A comparação antes/depois confirmou dados, objetos, roles, grants, extensões e histórico inalterados no inventário. Não foi executado contra banco persistente.  
**Arquivos:** `DatabaseInventoryCommand.java`, `JavaModernizationPlatformApplication.java`, `DatabaseInventoryCommandIntegrationTest.java` e `inventory-usage.md`. Migrations, configuração de produção, Docker Compose e roles existentes não foram alterados.

### T-05.3 — Implementar procedimento versionado de transição PostgreSQL

**Estado:** incompleta em 2026-11-06; autorização recebida e inspeção/análise realizadas; nenhum procedimento ou comando de transição implementado.  
**Resultado:** não foi possível demonstrar um desenho seguro de retomada com os contratos atuais. O inventário T-05.2 não verifica a capacidade da identidade provisionadora para criar roles/transferir ownership, e os dois estados iniciais têm necessidades diferentes: V1–V4 pode não ter roles alvo nem credenciais configuradas; V1–V5 exige preservar as credenciais das roles já existentes. O checkpoint também não tem localização definida que concilie durabilidade atômica e reconhecimento do inventário: arquivo externo pode divergir de commits PostgreSQL em queda; checkpoint dentro do banco cria objeto que o inventário atual recusa como extra.  
**Decisões técnicas pendentes:** canal de credenciais somente para roles ausentes; capacidades/memberships não-superuser da identidade de provisionamento; checkpoint durável com concorrência e recuperação após queda; reconhecimento fail-closed de prefixos parciais autorizados sem aceitar ownership misto arbitrário; inventário do estado final/checkpoint; grants exatos de sequências/colunas e owner esperado por tipo de objeto. As recomendações refinadas estão em `plan.md`, subseção T-05.3-B, e seguem sujeitas à aprovação explícita.  
**Limites desta execução:** nenhuma conexão com banco, fixture, DDL/DML, teste, build ou alteração de migration/configuração de produção; nenhum comando Git. A T-05.3 não está concluída. Não avançar para T-05.4 nem T-06.

### T-05.3-A — Definir contrato de execução, checkpoints e recuperação

**Estado:** análise documental concluída em 2026-11-06; recomendações D-06 a D-10 registradas como propostas e aguardam aprovação explícita. A T-05.3 continua incompleta.  
**Escopo executado:** comparação de alternativas de journal/checkpoint, identidade provisionadora, credenciais e roles, matriz de ownership/grants derivada de V1–V5, estados parciais e condições operacionais. Nenhuma recomendação foi registrada como decisão aprovada.  
**Recomendação central:** journal de transição no PostgreSQL, com alteração e checkpoint gravados no mesmo commit; arquivo externo somente como cópia operacional. A T-05.2 deverá futuramente reconhecer apenas o journal exato em progresso como transição em andamento, mantendo-o distinto dos estados iniciais suportados; isso também requer autorização e testes antes da implementação.  
**Aprovações necessárias:** (D-06) journal e tratamento transitório no inventário; (D-07) perfil não-superuser da identidade provisionadora e `CREATEROLE` condicional em V1–V4; (D-08) canal seguro para credenciais apenas de roles ausentes e condição de validação; (D-09) ownership/grants propostos, preservação dos owners de schema/extensão e ACL das funções V4/V5; (D-10) prefixos parciais retomáveis, journal íntegro e aborto em divergência. Detalhes refinados e matriz de recuperação em `plan.md`, subseção “T-05.3-B”; resumo para decisão em `spec.md`, seção 4.1.  
**Evidências/limites:** inspeção somente de arquivos e SQL versionado, mais consulta à documentação oficial PostgreSQL 16 para memberships, `CREATEROLE` e ownership. Nenhum banco foi conectado; nenhum segredo foi consultado; nenhum código, teste, configuração ou migration foi alterado; nenhum build/teste/Git foi executado.

### T-05.3-B — Refinar e fechar decisões arquiteturais

**Estado:** análise documental concluída em 2026-11-06; D-06 a D-10 permanecem propostas sem aprovação. T-05.3 permanece incompleta.  
**Escopo:** transformar as alternativas da T-05.3-A em contratos verificáveis para journal/checkpoints, identidade provisionadora, canal de credenciais, ownership/grants e recuperação. Documentar campos e proteção do journal, gaps do inventário, fronteiras não atômicas, matriz de classes de objetos, sequência de estados, retomadas permitidas e condições de aborto.  
**Resultado:** propostas especificam fingerprint e hash do inventário, identidade/tempo/evento e cadeia de evidências; perfil e capacidades por estágio da provisionadora; credenciais sem segredo em journal/log e sem redefinição automática; matriz para database, schemas, objetos V1–V5, extensão e Flyway; estados forward-only e reconciliação fail-closed. O inventário atual não coleta `rolbypassrls`, `rolvaliduntil` ou `rolconfig`; sua alteração é dependência futura, não autorizada nesta subtarefa.  
**Limites/evidências:** inspeção estática de SDD, código, SQL e testes existentes, mais consulta a documentação oficial do PostgreSQL 16. Não houve banco, implementação, migration, teste, build, configuração de produção ou consulta de segredo. Somente `spec.md`, `plan.md` e `tasks.md` foram atualizados.  
**Próxima etapa:** aguardar aprovação explícita, decisão por decisão, de D-06 a D-10. Não iniciar implementação, T-05.4 ou T-06 até autorização própria.

### T-05.3-C — Fechar lacunas do inventário e revisar suficiência de segurança

**Estado:** auditoria documental concluída em 2026-11-06; nenhuma alteração de implementação. D-06 a D-10 permanecem propostas sem aprovação; T-05.3 permanece incompleta.  
**Inspeção:** `DatabaseInventoryCommand`, testes do inventário, `inventory-usage.md`, `provision-postgres-roles.sql`, migrations V1–V5 e implementações/testes existentes de bootstrap, Flyway, roles e privilégios.  
**Conclusão:** atributos são consultados apenas para `app_migrator`, `app_runtime` e `app_bootstrap`; `rolbypassrls`, `rolvaliduntil` e `rolconfig` não são coletados. Memberships exibem opções PG16 apenas para vínculos diretos das roles-alvo; não há grafo transitivo nem perfil da provisionadora. Privilégios efetivos cobrem apenas algumas operações/alvos e não atribuem origem direta, herdada, PUBLIC, ownership ou atributos. Database, defaults de ACL, journal e várias classes/propriedades de objetos/dependências não são integralmente identificados. A tabela em `plan.md`, T-05.3-C, registra cobertura, severidade, critério futuro, contrato mínimo e impacto por D-06–D-10.  
**Regra fail-closed proposta:** valor ausente/nulo, não aplicável, não coletado e não verificável são estados distintos; nenhum dado ausente prova segurança. Para aprovar classificação/transição, todos os elementos obrigatórios devem ser verificados e objetos/capacidades devem estar na allowlist. O inventário atual é somente candidato limitado aos estados T-05.1, não prova de autoridade segura nem de retomada.  
**Limites:** somente inspeção estática e documentação nos três arquivos SDD permitidos. Não houve banco, segredo, implementação, migration, teste, build ou comando Git. Não foram alterados Java, testes ou scripts. A ampliação do inventário exige autorização futura própria; nenhuma proposta D-06–D-10 foi aprovada.

### T-05.3-D — Planejar a evolução do inventário PostgreSQL

**Estado:** planejamento documental concluído em 2026-11-12; nenhuma implementação autorizada ou iniciada. D-06–D-10 seguem pendentes e T-05.3 permanece incompleta.  
**Objetivo:** decompor as lacunas da auditoria T-05.3-C em evolução incremental e testável do inventário read-only.  
**Arquitetura planejada:** evidências versionadas por campo/principal e fotografia PG16 read-only; coletores de roles, memberships, ACL/ownership/objetos, efetivos/proveniência, journal, integridade e evidências externas; classificador compõe os resultados fail-closed. Valores de configuração potencialmente sensíveis são avaliados contra allowlist e redigidos; nenhum segredo é reportado.  
**Subtarefas futuras e dependências:**

| Subtarefa | Escopo/objetivo | Dependências | Arquivos previstos | Prova PG16 e critério resumido |
|---|---|---|---|---|
| T-05.3-D1 | Estados de coleta e nove atributos para app roles, provisionadora, sessão, owners e roles relevantes; redaction. | T-05.3-C | `DatabaseInventoryCommand.java`, `DatabaseInventoryCommandIntegrationTest.java`, `inventory-usage.md` | Atributos verdadeiros/falsos/nulos, roles ausentes, config sensível, visibilidade insuficiente; todo campo tem origem/status e dado ausente bloqueia. |
| T-05.3-D2 | Grafo member/granted role, grantor e opções PG16; capacidades transitivas SET/INHERIT/ADMIN e caminhos. | D1 | Mesmos arquivos do inventário | Vínculos diretos/indiretos, opções, SET possível/impossível, herança, ADMIN e ciclos inesperados; paths explicados e grafo incompleto bloqueia. |
| T-05.3-D4 | Ownership, ACLs, objetos e dependências por classe; OID e limites. | D1; base para D3/D5/D7 | Mesmos arquivos do inventário | Database, schemas, relações, índices, constraints, sequences, funções/procedures, triggers, types, extensões, Flyway, defaults; ACLs nulas/PUBLIC, OIDs/recriação e desconhecidos. |
| T-05.3-D3 | Privilégios efetivos e proveniência direta, herdada, PUBLIC, ownership e administrativa. | D1, D2, D4 | Mesmos arquivos do inventário | Cruzar explicação com funções `has_*` e tentativas por conexões fixture; privilégio sem fonte explicada bloqueia. |
| T-05.3-D5 | Reconhecimento de journal/checkpoints, sequência, cadeia, identidade e correspondência com efeitos. | D1–D4 e aprovação explícita D-06/D-10 | Mesmos arquivos do inventário | Journal válido/inválido/duplicado/divergente, eventos faltantes e efeitos sem checkpoint; inventário não grava journal e não amplia T-05.1. |
| T-05.3-D6 | Dados/integridade e evidências externas de backup, consumers, credenciais, janela/corte/aprovação. | D1/D4; integra com D7 | Mesmos arquivos; contrato externo pode requerer documentação adicional ainda sujeita a autorização | Snapshot, contagens/constraints/sequences/auditoria/Flyway e diferenças demonstram que contagem isolada não prova preservação; evidência externa separada, rastreável e sem segredo. |
| T-05.3-D7 | Consolidar formato, classificação, bloqueadores e relatório read-only. | D1–D6; decisões aprovadas quando aplicáveis | `DatabaseInventoryCommand.java`, `JavaModernizationPlatformApplication.java` somente se necessário, teste de integração e `inventory-usage.md` | Matriz ponta a ponta T-05.1, UNKNOWN/REJECTED, sem falsos `SUPPORTED`, segredo ausente e comparação antes/depois comprova ausência de mutação. |

**Sequência recomendada:** D1 → D2 e D4 (após D1; podem ocorrer em paralelo) → D3 (após D2/D4) → D5 (após D1–D4 e aprovação D-06/D-10) e D6 (pode ocorrer após D1/D4) → D7. Cada subtarefa requer autorização individual e segue uma por vez; conclusão de uma não autoriza a seguinte.  
**Estratégia de testes:** PostgreSQL 16 descartável via Testcontainers; setup/principals separados; fixture preparada antes da leitura; chamadas do inventário em READ ONLY; comparar dados, roles, memberships, ownership, grants, extensões, Flyway e journal antes/depois; falha de visibilidade deve ser NOT_VERIFIABLE. Testes de transição são separados e dependem de autorização posterior.  
**Limites e riscos:** OIDs não são identidade global/perpétua; mudança entre snapshot e uso é TOCTOU; visibilidade dos catálogos, tamanho do grafo/relatório, custo de varreduras e redaction de `rolconfig` podem impedir prova; journal não é prova contra owner/superuser; checksums/digests podem vazar informação. Manter fail-closed e documentar cobertura. Nada nesta tarefa altera código, testes, migrations ou configuração, conecta a banco, executa build/testes ou aprova D-06–D-10.

## T-06 — Verificação integrada e encerramento

**Estado:** pendente de autorização.  
**Objetivo:** comprovar critérios CA-01–CA-14 e registrar limitações reais.  
**Decisões relacionadas:** todas as decisões aprovadas.  
**Escopo:** regressão pertinente, testes PostgreSQL, fluxo de bootstrap, grants, evidências da transição realizada em T-05 e imagem final; corrigir somente defeitos autorizados nesta iniciativa.

**Critérios de conclusão:** cada CA ligado a evidência real; testes Maven pertinentes passam; Testcontainers comprova roles independentes; comando comprova idempotência/conflitos/corrida e sigilo; evidências de T-05 demonstram preservação dos dados e estado Flyway na transição; imagem executa com UID/GID não zero, health e permissões corretas; documentação e matriz atualizadas. Sem declarar propriedades não exercitadas.

**Evidências esperadas:** comandos e resultados reais; roles/grants usados; testes negados e aceitos; estado de dados antes/depois; logs sem segredo; UID/GID observados; limitações explicitadas.

**Dependências:** T-02, T-03, T-04 e T-05; ambiente Docker/Testcontainers disponível.

## Ordem de execução recomendada

**T-01 → T-03 → T-02 → T-04 → T-05 → T-06.** T-03 precede a integração de T-02 para garantir que o comando use role exclusiva sem expor credenciais privilegiadas ao runtime. T-04 pode ser desenvolvido em paralelo após T-01 e autorização própria. A ordem não concede autorização.

## Registro desta revisão

Esta correção documental consolida o contrato de T-01: a API precisa inserir usuários USER no cadastro público, enquanto a proteção contra criação/promoção ADMIN deve ser imposta no PostgreSQL; registra também códigos de saída e normalização de e-mail. T-01 está concluída apenas como análise documental. T-02 e T-03 não foram iniciadas. Nenhum código, teste, migration, configuração, Docker ou infraestrutura foi alterado; não foram executados testes, build, bootstrap ou operação de banco. A ordem permanece T-01 → T-03 → T-02 → T-04 → T-05 → T-06.
