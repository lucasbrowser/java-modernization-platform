# Tarefas: Observabilidade da API

As tarefas abaixo são para a etapa de implementação posterior. Cada item tem uma verificação objetiva e está associado aos critérios de aceitação da especificação.

## T-01 — Confirmar decisões de segurança e readiness ✅

- **Dependências:** nenhuma.
- **Critérios:** RF-03, RF-05, CA-02, CA-03, CA-04, CA-06.
- **Escopo provável:** decisão registrada em `plan.md`; pode afetar `SecurityConfig.java`, `application.yml`, `monitoring/prometheus.yml` e `docker-compose.yml`.
- **Verificação:** mecanismo de autenticação do scraper, política do PostgreSQL no readiness e URL/porta documentada ficam explícitos antes de codificar.

- **Decisão aprovada:** autenticação Basic dedicada ao scraper, com credenciais externas; readiness mantém os grupos padrão do Actuator (sem adicionar PostgreSQL nesta tarefa); gerenciamento permanece na porta 8081, acessível ao Prometheus na rede interna do Compose.

## T-02 — Ajustar acesso autorizado ao endpoint Prometheus 🟡

- **Dependências:** T-01.
- **Critérios:** RF-05, CA-03, CA-04.
- **Escopo provável:** `SecurityConfig.java`, `monitoring/prometheus.yml` e possivelmente `docker-compose.yml`.
- **Verificação:** chamada anônima a `/actuator/prometheus` recebe rejeição de autenticação; cliente Prometheus configurado recebe resposta no formato scrape esperado; nenhuma credencial é versionada.

- **Implementado; validação parcial:** cadeia Basic isolada para `/actuator/prometheus`; Prometheus e API recebem credenciais por variáveis de ambiente; testes provam rejeição anônima e autenticação Basic no path de scrape. A validação do scrape contra o Actuator e Prometheus reais ficou pendente porque o ambiente de teste não tem Docker disponível.

## T-03 — Definir e validar a política de health, liveness e readiness

- **Dependências:** T-01.
- **Critérios:** RF-01, RF-02, RF-03, CA-01, CA-02.
- **Escopo provável:** `application.yml` apenas se necessário e novos testes de Actuator.
- **Verificação:** testes exercitam health, liveness e readiness saudáveis, além do comportamento esperado conforme decisão sobre dependências; detalhes sensíveis permanecem ocultos.

## T-04 — Cobrir a segurança dos endpoints de observabilidade

- **Dependências:** T-02, T-03.
- **Critérios:** RF-05, CA-04, CA-05.
- **Escopo provável:** novos testes em `src/test/java/com/github/lucasoliveira/platform/` junto da configuração de segurança/Actuator.
- **Verificação:** testes comprovam endpoints públicos explicitamente autorizados, rejeição anônima das métricas e acesso do cliente autorizado; suíte preexistente preservada.

## T-05 — Documentar URLs, estados e acesso

- **Dependências:** T-01, T-02, T-03.
- **Critérios:** CA-01, CA-02, CA-03, CA-06.
- **Escopo provável:** `README.md`.
- **Verificação:** documentação lista health, liveness, readiness, métricas, porta/path efetivos e requisitos de autenticação sem expor segredos.

## T-06 — Executar validação da suíte e dos critérios

- **Dependências:** T-02, T-03, T-04, T-05.
- **Critérios:** CA-01 a CA-06.
- **Escopo provável:** sem alteração adicional se tudo passar.
- **Verificação:** executar os testes pertinentes e comparar evidências com cada critério; registrar falhas/limitações. Incluir `mvn clean test` quando o ambiente permitir e validar a configuração de scrape do Prometheus conforme ambiente disponível.
