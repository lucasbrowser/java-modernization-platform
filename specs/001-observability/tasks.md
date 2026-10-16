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

- **Implementado; validação parcial:** cadeia Basic isolada para `/actuator/prometheus`; Prometheus e API recebem credenciais por variáveis de ambiente. Os testes usam `@WebMvcTest`, importam `SecurityConfig` e exercitam a cadeia Basic nesse path com um endpoint de teste; não validam o scrape contra o Actuator e Prometheus reais. Essa validação integrada permanecia pendente naquele momento; a justificativa registrada na execução original foi a indisponibilidade de Docker naquele ambiente de teste.

- **Validação operacional concluída posteriormente:** a configuração Basic foi validada no Docker Compose. O comando `promtool check config /etc/prometheus/prometheus.yml` confirmou a sintaxe da configuração do Prometheus. O alvo `api:8081/actuator/prometheus` apresentou estado `UP`; a consulta `up{job="java-modernization-platform"}` retornou `1`, e `scrape_samples_scraped` retornou `178`. A requisição sem credenciais retornou HTTP `401`. A configuração gerada e o arquivo de senha são locais e ignorados pelo Git. Nenhum valor de credencial foi registrado.


## T-03 — Definir e validar a política de health, liveness e readiness ✅

- **Dependências:** T-01.
- **Critérios:** RF-01, RF-02, RF-03, CA-01, CA-02.
- **Escopo:** testes de integração do Actuator; configuração de produção preservada.
- **Decisões aprovadas:** manter os grupos padrão de probes do Actuator, sem adicionar PostgreSQL ao grupo readiness; manter `management.endpoint.health.show-details: never`; preservar a porta de gerenciamento separada da porta principal da API.
- **Implementação:** criado `src/test/java/com/github/lucasoliveira/platform/ActuatorHealthIntegrationTest.java`. O teste inicializa o contexto Spring Boot com PostgreSQL via Testcontainers e porta de gerenciamento dinâmica.
- **Cobertura:** health, liveness e readiness são acessados sem autenticação e devem retornar HTTP 200, status `UP` e nenhuma informação sensível nos campos verificados.
- **Validação:** o teste `ActuatorHealthIntegrationTest` passou isoladamente. A suíte completa também passou com `mvn clean test`, sem erros reportados.
- **Resultado:** T-03 concluída dentro do escopo aprovado. Não foi necessária alteração em `application.yml`, `pom.xml`, `docker-compose.yml` ou `SecurityConfig.java`.


## T-04 — Cobrir a segurança dos endpoints de observabilidade

- **Dependências:** T-02, T-03.
- **Critérios:** RF-05, CA-04, CA-05.
- **Escopo provável:** novos testes em `src/test/java/com/github/lucasoliveira/platform/` junto da configuração de segurança/Actuator.
- **Implementação:** `ActuatorSecurityIntegrationTest` usa o servidor de gerenciamento em porta dinâmica, PostgreSQL via Testcontainers e chamadas HTTP ao Actuator real. A configuração do teste ativa explicitamente `management.prometheus.metrics.export.enabled=true`; diagnósticos temporários e logging TRACE/DEBUG foram removidos.
- **Causa raiz do 404:** no teste com porta de gerenciamento separada, a exportação Prometheus não era ativada no contexto testado sem a propriedade explícita, apesar da dependência de registry estar no classpath e do endpoint constar na lista de exposição. Com a propriedade ativa, o endpoint real passa a responder 200 e conteúdo no formato Prometheus. A configuração de produção não foi alterada; o scrape funcional pelo Compose já estava registrado anteriormente.
- **Cobertura:** HTTP 401 para scrape anônimo e credenciais Basic inválidas; HTTP 200 e linhas `# HELP` ou `# TYPE` para credenciais válidas; HTTP 401 anônimo para `/actuator/info` e `/actuator/metrics`.
- **Validação:** `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" -Dtest=ActuatorSecurityIntegrationTest test` — 5 testes, 0 falhas, 0 erros; `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" -Dtest=ActuatorHealthIntegrationTest test` — 3 testes, 0 falhas, 0 erros; `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean test` — 95 testes, 0 falhas, 0 erros, 0 ignorados.
- **Resultado:** T-04 concluída. Os testes automatizados verificam o endpoint real, preservam PostgreSQL/Testcontainers e não adicionam credenciais reais.

## T-05 — Documentar URLs, estados e acesso

- **Dependências:** T-01, T-02, T-03.
- **Critérios:** CA-01, CA-02, CA-03, CA-06.
- **Escopo provável:** `README.md`.
- **Implementação:** seção “Observabilidade” adicionada ao README junto da execução da aplicação, com portas e caminhos separados da API e do Actuator, endpoints, finalidade, autenticação e respostas esperadas. A porta de gerenciamento não publicada no host pelo Compose e o acesso interno do Prometheus também foram descritos.
- **Validação:** URLs e políticas conferidas em `application.yml`, `docker-compose.yml`, `monitoring/prometheus.yml`, `SecurityConfig.java`, `ActuatorHealthIntegrationTest` e `ActuatorSecurityIntegrationTest`. A documentação não inclui valores de credenciais. Como a alteração é exclusivamente documental, não foram criados nem executados testes automatizados.
- **Critérios:** CA-01 e CA-02 atendidos pela descrição de health/liveness/readiness e dos estados `UP`/HTTP 200 quando saudável; CA-03 atendido pela descrição do scrape Prometheus autenticado por HTTP Basic; CA-06 atendido pela documentação dos endpoints e requisitos de acesso.
- **Resultado:** T-05 concluída. Nenhuma configuração de produção ou funcionalidade foi alterada.

## T-06 — Executar validação da suíte e dos critérios

- **Dependências:** T-02, T-03, T-04, T-05.
- **Critérios:** CA-01 a CA-06.
- **Escopo provável:** sem alteração adicional se tudo passar.
- **Verificação:** executar os testes pertinentes e comparar evidências com cada critério; registrar falhas/limitações. Incluir `mvn clean test` quando o ambiente permitir e validar a configuração de scrape do Prometheus conforme ambiente disponível.
