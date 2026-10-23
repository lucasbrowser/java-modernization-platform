# Tarefas — Iniciativa 006

**Status da iniciativa:** aguardando aprovação do plano revisado  
**Autorização vigente:** apenas correção documental. T-01 (análise e fechamento do contrato) foi concluída documentalmente; nenhuma implementação foi iniciada. T-02, T-03 e demais tarefas dependem de autorização explícita.

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

**Estado:** pendente de autorização.  
**Objetivo:** permitir provisionar o primeiro ADMIN com segurança operacional, sem endpoint HTTP ou seed de startup.  
**Decisões relacionadas:** D-01, D-02, D-03, DP-01, DP-02 e DP-05.  
**Escopo:** comando one-shot; senha digitada em terminal interativo, sem eco via stdin; role bootstrap exclusiva; validação/encoder existentes; transação, concorrência e respostas operacionais definidas. Nenhum secret bootstrap na API normal. Execução automatizada do bootstrap fica fora até uma decisão operacional específica.

**Critérios de conclusão:** sem ADMIN criado no startup; primeiro ADMIN criado por invocação explícita; mesmo ADMIN/e-mail retorna no-op e preserva hash; USER/e-mail igual retorna conflito sem promoção; outro ADMIN existente recusa criação sem alteração; corrida não cria duplicata; rollback integral em falha; nenhuma senha aparece em argumentos, saída ou logs.

**Testes/evidências esperadas:** testes de entrada/validação interativa; PostgreSQL com conexões independentes para criação, no-op, conflitos e concorrência; verificação de hash imutável e rollback; teste de startup sem seed; inspeção de saída/logs sem senha. Teste unitário isolado não comprova role operacional nem concorrência.

**Dependências:** T-01; T-03 deve preceder a integração do comando para fornecer role/credencial bootstrap separada. Qualquer desenvolvimento isolado ainda requer autorização para T-02.

## T-03 — Separar provisionamento, migrations e runtime; migrar bancos existentes

**Estado:** pendente de autorização.  
**Objetivo:** garantir migrations fora do processo normal da API e papel runtime com privilégios mínimos, preservando bancos atuais.  
**Decisões relacionadas:** D-04, DP-03, DP-04 e DP-05.  
**Escopo:** identidade externa de provisionamento, migrator, runtime e bootstrap; job/processo Flyway one-shot; grants mínimos; extensão `pgcrypto`; transição de banco novo/existente forward-only e recuperação operacional; adaptações Testcontainers. Não editar migrations aplicadas nem apagar volumes.

**Critérios de conclusão:** migrations funcionam com migrator não superusuário; API inicia com runtime e `ddl-auto: validate`, sem configuração bootstrap obrigatória; API não recebe secrets de migration/bootstrap; runtime consegue registrar USER e executar SQL dos fluxos suportados, inclusive Customer-link e INSERT de auditoria, mas PostgreSQL rejeita INSERT de ADMIN, promoção de USER para ADMIN e tentativa runtime de alterar/desativar a proteção; runtime não consegue DDL/CREATE ROLE/DB/DELETE/TRUNCATE nem modificar/remover auditoria; bootstrap role é exclusiva e não alcançável pela API; transição preserva dados e estado Flyway; falha bloqueia rollout sem fallback a superuser.

**Testes/evidências esperadas:** PostgreSQL 16 Testcontainers com conexões independentes; banco vazio; migração por Flyway role não superusuária, incluindo prova da extensão `pgcrypto`; API com runtime e `ddl-auto: validate` sem variáveis bootstrap; comprovar INSERT de USER permitido, INSERT de ADMIN e promoção USER→ADMIN negados, e proteção não alterável/desativável pelo runtime; comprovar operações Customer-link/auditoria permitidas e mutação/remoção de auditoria negada; grants permitidos/negados; fixture/clone pré-existente com verificação de dados/ownership/history; ordem e falha do job. Adaptar testes com `@ServiceConnection` sem reduzir cobertura.

**Dependências:** T-01. Seleção técnica de executor e grants ocorre após prova. T-02 integrado depende de T-03 concluída.

## T-04 — Executar a imagem como usuário não privilegiado

**Estado:** pendente de autorização.  
**Objetivo:** executar a aplicação em imagem final com UID/GID numéricos estáveis não root.  
**Decisões relacionadas:** D-05, DP-06.  
**Escopo:** permissões mínimas para JAR, certificados, configuração e diretórios temporários; compatibilidade JVM, Postgres e health checks. Não alterar privilégios de aplicação fora da iniciativa.

**Critérios de conclusão:** execução observada com UID/GID configurados diferentes de zero; JVM/API inicia e conecta ao Postgres com runtime role; health checks funcionam; artefatos são legíveis; escrita só ocorre em diretórios requeridos; temporários funcionam; diretórios desnecessários não são graváveis.

**Testes/evidências esperadas:** build e execução da imagem; captura de UID/GID efetivos; smoke check de startup/health e conexão; verificação de leitura de truststore e diretório temporário. Presença de `USER` isoladamente não satisfaz a tarefa.

**Dependências:** T-01 para mapear filesystem e requisitos; operação pode ser desenvolvida após autorização sem aguardar T-02/T-03, mas integração final é T-06.

## T-05 — Configuração e documentação operacional

**Estado:** pendente de autorização.  
**Objetivo:** deixar a configuração reproduzível por ambiente e documentar execução/recuperação.  
**Decisões relacionadas:** D-01–D-05, DP-01–DP-06.  
**Escopo:** application config, Compose, `.env.example` e README; variáveis por serviço; sem fallbacks inseguros; ordem de provisionamento, Flyway, bootstrap e API; ambiente local/teste/produção; stdin interativo e limite de automação; procedimento para bancos existentes e recuperação. Não acessar `.env` real.

**Critérios de conclusão:** API recebe apenas runtime credential; migration e bootstrap credentials chegam só aos seus processos autorizados; Compose aguarda migration antes de declarar API pronta; ausência de segredo falha sem revelá-lo; exemplos são placeholders inutilizáveis; runbook não propõe destruição nem rollback automático de migrations.

**Testes/evidências esperadas:** validação de configuração ausente; validação do modelo Compose e ordem dos serviços; inspeção das variáveis/segredos por serviço; revisão documental dos passos e placeholders; teste operacional de runbook em clone quando T-03 fornecer fixture.

**Dependências:** T-01 e resultados de T-02, T-03 e T-04 para documentar comportamento final. Coordenar alterações para não interromper execução local.

## T-06 — Verificação integrada e encerramento

**Estado:** pendente de autorização.  
**Objetivo:** comprovar critérios CA-01–CA-14 e registrar limitações reais.  
**Decisões relacionadas:** todas as decisões aprovadas.  
**Escopo:** regressão pertinente, testes PostgreSQL, fluxo de bootstrap, grants, transição e imagem final; corrigir somente defeitos autorizados nesta iniciativa.

**Critérios de conclusão:** cada CA ligado a evidência real; testes Maven pertinentes passam; Testcontainers comprova roles independentes; comando comprova idempotência/conflitos/corrida e sigilo; transição preserva dados; imagem executa com UID/GID não zero, health e permissões corretas; documentação e matriz atualizadas. Sem declarar propriedades não exercitadas.

**Evidências esperadas:** comandos e resultados reais; roles/grants usados; testes negados e aceitos; estado de dados antes/depois; logs sem segredo; UID/GID observados; limitações explicitadas.

**Dependências:** T-02, T-03, T-04 e T-05; ambiente Docker/Testcontainers disponível.

## Ordem de execução recomendada

**T-01 → T-03 → T-02 → T-04 → T-05 → T-06.** T-03 precede a integração de T-02 para garantir que o comando use role exclusiva sem expor credenciais privilegiadas ao runtime. T-04 pode ser desenvolvido em paralelo após T-01 e autorização própria. A ordem não concede autorização.

## Registro desta revisão

Esta correção documental consolida o contrato de T-01: a API precisa inserir usuários USER no cadastro público, enquanto a proteção contra criação/promoção ADMIN deve ser imposta no PostgreSQL; registra também códigos de saída e normalização de e-mail. T-01 está concluída apenas como análise documental. T-02 e T-03 não foram iniciadas. Nenhum código, teste, migration, configuração, Docker ou infraestrutura foi alterado; não foram executados testes, build, bootstrap ou operação de banco. A ordem permanece T-01 → T-03 → T-02 → T-04 → T-05 → T-06.
