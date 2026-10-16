# Plano técnico: Observabilidade da API

## Estado observado no diagnóstico inicial (antes da T-02)

> Os itens desta seção e a situação inicial da tabela seguinte registram o estado observado antes da implementação da T-02. Eles não descrevem integralmente o estado posterior.

- `pom.xml` usa Java 21 e Spring Boot 3.3.5. Já contém `spring-boot-starter-actuator` e `micrometer-registry-prometheus`; não há necessidade identificada de adicionar dependências.
- `src/main/resources/application.yml` já define porta de gerenciamento 8081, expõe `health`, `info`, `metrics` e `prometheus`, esconde detalhes de health e habilita probes. Histograma de `http.server.requests` também está ativo.
- No diagnóstico anterior à T-02, `SecurityConfig` permitia `/actuator/health` e subpaths e também deixava `/actuator/prometheus` sem autenticação. A T-02 posterior isolou esse endpoint em uma cadeia HTTP Basic; o restante da API segue a cadeia JWT principal.
- No diagnóstico anterior à T-02, `monitoring/prometheus.yml` apontava para `api:8081/actuator/prometheus` sem credenciais de scrape. Após a T-02, a configuração usa HTTP Basic com credenciais externas. A validação integrada foi concluída: alvo UP, consulta up=1, 178 amostras e acesso anônimo com HTTP 401.
- `docker-compose.yml` mantém a porta 8081 sem publicação no host e conecta API e Prometheus à rede `monitoring`; a API expõe no host somente 8080. Isso limita a exposição externa, mas não substitui a proteção de autorização do endpoint.
- `README.md` documenta o health check em `http://localhost:8080/api/actuator/health`, mas não descreve liveness, readiness, endpoint Prometheus nem requisitos de acesso.
- Há testes de integração de controllers com `SecurityConfig` e cobertura de rejeição anônima em recursos protegidos. Não foi encontrado teste de Actuator, probes ou autorização de métricas. `application-test.yml` não define configuração específica de Actuator.
- Controllers de negócio existentes: auth, customer, product e order. Nenhum endpoint de observabilidade customizado foi encontrado; o comportamento deve continuar no Actuator.

## Comparação com requisitos e critérios

| Item | Situação atual | Lacuna / trabalho previsto |
|---|---|---|
| RF-01 / CA-01 health check | Actuator presente; health exposto e detalhes ocultos. README cita a URL de aplicação. | Criar teste de resposta saudável e revisar a documentação da URL efetiva. A porta de gerenciamento 8081 tem context path `/api`? Validar a configuração efetiva durante implementação. |
| RF-02 / RF-03 / CA-02 liveness e readiness | `management.endpoint.health.probes.enabled: true`. | Não há testes nem documentação dos grupos de probes. Definir se readiness considera o PostgreSQL; habilitar probes não significa necessariamente incluir todas as dependências externas no grupo de readiness. |
| RF-04 / CA-03 métricas Prometheus | Dependência Micrometer Prometheus, endpoint exposto, scrape configurado e dashboard Grafana existente. | Scrape funcional confirmado com autenticação autorizada: alvo UP, consulta up=1 e 178 amostras; não duplicar registry, dashboard ou job de scrape sem necessidade. |
| RF-05 / CA-04 segurança | Diagnóstico inicial, antes da T-02: health e Prometheus estavam permitidos sem autenticação. Após a T-02: health permanece público e Prometheus exige HTTP Basic. | A autenticação Basic e sua rejeição/análise em MockMvc estão registradas na T-02. A validação integrada foi concluída: alvo UP, consulta up=1, 178 amostras e acesso anônimo com HTTP 401. |
| RNF compatibilidade e dependências | Java 21, Boot 3.3.5, dependências de Actuator e Prometheus já presentes. | Preservar versões e evitar dependências adicionais, salvo necessidade comprovada pela decisão de autenticação. |
| RNF arquitetura modular | Observabilidade configurada transversalmente no app, sem módulo dedicado ou microserviço. | Manter configuração no Actuator/Security comum; não introduzir módulo/serviço novo. |
| RNF dados sensíveis | `show-details: never`; porta de gerenciamento não publicada no host pelo Compose. | Manter ocultação de detalhes e revisar exposição de `info`/métricas e limites de rede ao documentar. |
| CA-05 testes existentes | Testes unitários e de integração presentes nos módulos; nenhum teste de observabilidade localizado. | Executar suíte existente e adicionar testes de Actuator/proteção como parte da implementação futura. |
| CA-06 documentação | README contém URL de health apenas. | Documentar health, liveness, readiness, métricas e política de acesso/autorização aprovada. |

## Estratégia técnica proposta

1. Confirmar as decisões pendentes abaixo antes de alterar segurança ou o comportamento de readiness.
2. Reutilizar Actuator, Micrometer Prometheus, configuração de exposição e pipeline de scrape já existentes.
3. Ajustar somente a regra de autorização e a configuração do cliente Prometheus necessária à política aprovada, mantendo a cadeia JWT e o escopo público restritos aos endpoints expressamente autorizados.
4. Manter os grupos padrão de probes do Actuator, configurando dependências adicionais apenas conforme a decisão sobre PostgreSQL e readiness.
5. Adicionar cobertura de integração do Actuator para health/probes e do endpoint Prometheus para solicitações anônimas e autorizadas, sem alterar controllers de domínio.
6. Atualizar README e, se necessário para autenticação escolhida, `monitoring/prometheus.yml` e configuração do serviço `prometheus` em Compose. Evitar incluir segredo literal; utilizar configuração externa apropriada.
7. Validar critérios CA-01 a CA-06 e executar os testes pertinentes; a etapa atual não executa testes porque não implementa comportamento.

## Arquivos potencialmente afetados na implementação

- `pom.xml`: apenas se a implementação demonstrar lacuna de dependência; estado atual indica que não deve ser alterado.
- `src/main/resources/application.yml`: política dos grupos de health/probes, somente se a decisão exigir.
- `src/main/java/com/github/lucasoliveira/platform/common/security/SecurityConfig.java`: autorização específica do endpoint de métricas.
- `monitoring/prometheus.yml`: autenticação/configuração do scrape, dependendo da estratégia escolhida.
- `docker-compose.yml`: passagem de configuração externa ao Prometheus, se necessária; a porta de gerenciamento já não é publicada no host.
- `src/test/java/com/github/lucasoliveira/platform/...`: novos testes de integração de Actuator e autorização de métricas (local/pacote a decidir com as convenções existentes).
- `README.md`: URLs, estados e requisitos de acesso.

## Riscos

- Risco identificado antes da T-02: retirar `prometheus` da lista pública poderia interromper scrape sem credenciais alinhadas. A configuração Basic foi implementada; a validação integrada do scrape real foi concluída: alvo UP, consulta up=1, 178 amostras e acesso anônimo com HTTP 401.
- A porta de gerenciamento é 8081 e está em rede privada de Compose; a URL de health documentada em 8080 inclui `/api`. É necessário confirmar o path efetivo do management server em porta separada antes de publicar URLs como contrato.
- Incluir PostgreSQL no readiness pode retirar a instância do tráfego durante indisponibilidade do banco; deixá-lo fora pode declarar pronta uma API incapaz de servir operações dependentes do banco.
- A configuração atual expõe `info` e `metrics` no Actuator, mas essas rotas não estão publicamente autorizadas pela `SecurityConfig`; alterações amplas de matcher podem expô-las acidentalmente.
- Testes completos podem depender de Docker/Testcontainers conforme a suíte existente.

## Decisões pendentes

1. **Readiness e PostgreSQL:** incluir estado do banco no grupo `readiness` ou manter o readiness padrão baseado no estado da aplicação? A especificação deixa dependências conforme configuração adotada, sem determinar a política. Para T-02, foi aprovada a manutenção dos grupos padrão; a decisão final de readiness será tratada em T-03.
2. **Endereço de gerenciamento:** confirmar/documentar a URL externa esperada em 8081 (incluindo o context path efetivo) versus a URL atual de health em 8080. Para T-02, foi aprovado manter 8081 interna, acessível pelo scraper na rede Compose. Documentar URLs efetivas em T-05.

## Decisões aprovadas para T-02

- Prometheus usa HTTP Basic com usuário e senha dedicados, injetados por `PROMETHEUS_USERNAME` e `PROMETHEUS_PASSWORD`; nenhum segredo é armazenado no repositório.
- A autorização Basic fica isolada no matcher exato `/actuator/prometheus`; as demais rotas seguem a cadeia JWT já existente e health permanece público.
- A porta de gerenciamento 8081 segue interna no Compose, com o Prometheus como cliente autorizado.
- Readiness permanece com os grupos padrão do Actuator nesta tarefa, sem adicionar PostgreSQL. A decisão final é acompanhada em T-03.

