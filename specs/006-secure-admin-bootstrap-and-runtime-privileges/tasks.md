# Tarefas — Iniciativa 006

**Status da iniciativa:** em andamento; T-03 concluída com evidências em 2026-10-26.  
**Autorização vigente:** T-03 foi autorizada e validada nesta execução. T-04, T-05 e T-06 permanecem pendentes de autorização explícita. T-01 segue concluída documentalmente.

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

**Estado:** pendente de autorização.  
**Objetivo:** executar a aplicação em imagem final com UID/GID numéricos estáveis não root.  
**Decisões relacionadas:** D-05, DP-06.  
**Escopo:** permissões mínimas para JAR, certificados, configuração e diretórios temporários; compatibilidade JVM, Postgres e health checks. Não alterar privilégios de aplicação fora da iniciativa.

**Critérios de conclusão:** execução observada com UID/GID configurados diferentes de zero; JVM/API inicia e conecta ao Postgres com runtime role; health checks funcionam; artefatos são legíveis; escrita só ocorre em diretórios requeridos; temporários funcionam; diretórios desnecessários não são graváveis.

**Testes/evidências esperadas:** build e execução da imagem; captura de UID/GID efetivos; smoke check de startup/health e conexão; verificação de leitura de truststore e diretório temporário. Presença de `USER` isoladamente não satisfaz a tarefa.

**Dependências:** T-01 para mapear filesystem e requisitos; operação pode ser desenvolvida após autorização sem aguardar T-02/T-03, mas integração final é T-06.

## T-05 — Transição segura de bancos existentes e documentação operacional

**Estado:** pendente de autorização.  
**Objetivo:** migrar com segurança bancos existentes para os papéis e privilégios estabelecidos em T-03, preservando dados, e documentar a transição.  
**Decisões relacionadas:** D-04, DP-03–DP-05.  
**Escopo:** identificar e validar estados iniciais suportados; usar fixture, clone ou ambiente equivalente; inventariar schema, ownership, extensões, roles/grants, dados e histórico Flyway; aplicar procedimento versionado, idempotente e forward-only, sem reescrever migrations aplicadas; preservar dados e integridade referencial; verificar objetos, privilégios e estado Flyway após a transição; documentar pré-condições, ordem, verificações, falhas e recuperação, sem rollback automático ou fallback a superuser. Pode atualizar documentação operacional relacionada sem antecipar T-02. Não acessar `.env` real.

**Critérios de conclusão:** procedimento de transição foi exercitado em fixture/clone representativo; dados, integridade referencial e histórico Flyway são preservados; ownership, objetos e privilégios pós-transição são verificados contra os resultados de T-03; reexecução e falhas têm comportamento documentado e não destrutivo; recuperação, pré-condições e limites estão descritos; não há edição de migrations aplicadas nem declaração de transição para estados não testados.

**Testes/evidências esperadas:** comparação de inventário e dados antes/depois em clone/fixture; verificações de integridade referencial, ownership relevante, grants, objetos e histórico Flyway; teste de aplicação e reexecução do procedimento nos estados suportados; simulação segura/documentada das falhas recuperáveis; runbook com comandos, pré-condições, validação e recuperação. Não usar banco com dados do usuário.

**Dependências:** T-01 para critérios de estado inicial; T-03 para roles, grants e mecanismos de proteção; T-02 para integração bootstrap pertinente à configuração final; T-04 para configuração da imagem. Sem essas definições, não iniciar a transição. T-06 valida o resultado integrado.

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
