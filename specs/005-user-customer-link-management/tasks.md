# Tarefas: Administração de vínculo User–Customer

**Estado da iniciativa:** proposta, aguardando aprovação.  
**Regra SDD:** nenhuma tarefa abaixo está aprovada ou concluída; cada tarefa de implementação exige autorização explícita individual.

## T-01 — Contrato e regras do serviço

- **Estado:** concluída em 2026-10-22 para as regras do serviço e testes unitários; não implica conclusão da iniciativa.
- **Objetivo:** resolver as decisões restantes do contrato e implementar as regras de associação/troca/remoção no serviço transacional, usando a auditoria persistida existente.
- **Pré-requisitos:** autorização explícita de T-01 recebida. As regras de serviço implementadas seguem as recomendações documentadas; o contrato HTTP continua pendente para a T-02.
- **Arquivos/componentes prováveis:** novo `auth.service.UserCustomerLinkService`; `AuthenticatedUserResolver`; `AuthenticatedUserIdentity`; `UserRepository`; `CustomerRepository`; `UserCustomerLinkAuditRepository`; `UserCustomerLinkAudit`; testes `UserCustomerLinkServiceTest`.
- **Critérios de aceite:** associação gera `CREATE`; troca gera `CHANGE`; remoção efetiva gera `REMOVE`; repetição da associação e, se aprovada, remoção sem vínculo são no-op sem auditoria; ator/alvo/snapshots/justificativa vêm dos valores autorizados e persistidos; operações são transacionais; justificativa obrigatória é `strip()` antes de validar limite de 500 e persistir, preservando espaços internos, conforme decisão aprovada; testes unitários cobrem estados e erros.
- **Testes previstos:** unidade para todas as transições, usuário/Customer inexistentes, alvo ADMIN, repetição sem auditoria, justificativa ausente/vazia/com espaços/sobre limite e cálculo da ação/snapshots.
- **Implementação e evidências:** `UserCustomerLinkService` valida o ator persistido como ADMIN, bloqueia o alvo USER, aplica associação/troca/remoção, normaliza a justificativa, grava auditoria e retorna resultado com indicador de mudança. `UserRepository.findByIdForUpdate` usa `PESSIMISTIC_WRITE`. Repetição de associação e remoção sem vínculo não salvam usuário nem evento. `@Transactional` abrange alteração e `saveAndFlush` da auditoria.
- **Validação executada:** `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" -Dtest=UserCustomerLinkServiceTest test` — `BUILD SUCCESS`; 13 testes, 0 falhas, 0 erros, 0 ignorados. A primeira execução no sandbox foi impedida por acesso negado ao JAR `spring-security-web-6.3.4`; a repetição com permissão elevada compilou e passou.
- **Limites da evidência:** testes unitários não comprovam rollback transacional real nem aplicação/serialização efetiva do lock pelo PostgreSQL. Esses cenários permanecem para T-04. Não foram executados testes de controller/JWT nem a suíte completa.
- **Limites:** não criar controller, migration ou acesso ao histórico; não alterar registro/login, role ou autorização de pedidos; não ampliar handlers globais.

## T-02 — Expor API REST

- **Estado:** concluída em 2026-10-23 para a API REST, validação HTTP e testes MVC; não implica conclusão da iniciativa.
- **Objetivo:** publicar `PUT /admin/users/{userId}/customer-link` para associar/trocar e `POST /admin/users/{userId}/customer-link/removal` para remover, conforme contrato aprovado em T-01; ambos retornam DTO/status acordados.
- **Arquivos/componentes prováveis:** novo controller em `auth.controller`; novos DTOs em `auth.dto`; `GlobalExceptionHandler` somente para exceção específica aprovada e necessária; `UserCustomerLinkControllerIntegrationTest`.
- **Critérios de aceite:** mappings, métodos HTTP, corpos, respostas, status, validação e `ApiError` correspondem ao contrato aprovado; nenhuma rota de consulta/exportação de auditoria é criada; nenhum `actorUserId` é aceito na entrada.
- **Testes previstos:** MockMvc para sucesso, associação repetida, troca, remoção, validação e recursos inexistentes; verificar corpo e ausência de campos sensíveis.
- **Limites:** somente as rotas novas e seus DTOs; não alterar APIs de auth, customer, product ou order.
- **Implementação e evidências:** adicionados `AdminUserCustomerLinkController`, DTOs de associação/remoção/resposta e `AdminUserCustomerLinkControllerIntegrationTest`. O controller expõe PUT para associar/trocar e POST `/removal` para remover, ambos com `200`, encaminhando ao serviço T-01. Os DTOs aplicam `strip()` antes das validações `@NotBlank`/`@Size(max=500)`; o DTO de resposta inclui `customerId: null` explicitamente. A rota usa `@PreAuthorize("hasRole('ADMIN')")`; nenhuma identidade de ator é aceita no corpo.
- **Validação executada:** `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" "-Dtest=AdminUserCustomerLinkControllerIntegrationTest,UserCustomerLinkServiceTest" test` — `BUILD SUCCESS`; 23 testes, 0 falhas, 0 erros, 0 ignorados (10 MVC e 13 unitários T-01). Execuções iniciais encontraram falhas em expectativas/entrada do teste; os testes foram corrigidos e a execução final passou.
- **Limites da evidência:** testes MVC exercitam JWT assinado para as roles ADMIN/USER, a cadeia de filtros, status e formato JSON; o serviço é mockado, portanto papel ADMIN persistido, gravação/auditoria, atomicidade e lock PostgreSQL não são comprovados. Não foi testado socket HTTP.

## T-03 — Validar autorização e identidade persistida

- **Estado:** proposta; depende de T-01 e T-02 concluídas e aprovadas.
- **Objetivo:** provar autorização ADMIN pelo filtro JWT e a verificação adicional do papel persistido de ator e alvo.
- **Arquivos/componentes prováveis:** novo controller/service de T-01/T-02; `AuthenticatedUserResolver`; testes de integração de vínculo/JWT, preferencialmente em classe dedicada ou `PostgresContainerIntegrationTest`.
- **Critérios de aceite:** anônimo `401`; USER `403`; ADMIN autenticado e persistido pode operar; role ADMIN apenas no JWT, sem ator ADMIN persistido, não altera vínculo/auditoria; alvo persistido ADMIN é rejeitado conforme contrato de T-01; ator de auditoria sempre é o usuário persistido resolvido, não dado do cliente.
- **Testes previstos:** JWT assinado via `JwtService`, filtro e identidade persistida reais; checar estado do vínculo e auditoria em cada caso negado/autorizado.
- **Limites:** não reclassificar o HTTP do JWT sem usuário persistido fora da nova operação; não enfraquecer `SecurityConfig`; não alterar o JWT.

## T-04 — Comprovar persistência, rollback e concorrência

- **Estado:** proposta; depende de T-01 a T-03 concluídas e aprovadas.
- **Objetivo:** comprovar no PostgreSQL que vínculo e auditoria são atômicos e que alterações simultâneas no mesmo alvo não geram snapshots incoerentes.
- **Arquivos/componentes prováveis:** `UserRepository` com método de lock se aprovado pela implementação; novo `UserCustomerLinkPostgresIntegrationTest`; Testcontainers existente; trigger/falha de teste isolada sem artefato de produção.
- **Critérios de aceite:** teste PostgreSQL observa lock/serialização da linha de User; falha real ao gravar auditoria reverte `customer_id`; sequência de eventos corresponde à cadeia de valores anterior/novo persistidos; todos os invariantes de FK e append-only permanecem válidos.
- **Testes previstos:** PostgreSQL/Testcontainers com conexões/transações independentes, barreira concorrente e estado final consultado após commit/rollback; nenhuma alegação baseada apenas em mocks.
- **Limites:** não alterar migration aplicada sem autorização específica; não adicionar endpoint de histórico; não executar testes de concorrência de cancelamento da iniciativa 004 nesta tarefa.

## T-05 — Regressão final e fechamento

- **Estado:** proposta; depende de T-01 a T-04 concluídas e aprovadas.
- **Objetivo:** mapear todos os critérios de `spec.md`, executar regressão completa e registrar resultados e riscos residuais.
- **Arquivos prováveis:** `specs/005-user-customer-link-management/spec.md`, `plan.md`, `tasks.md`; código/testes somente se defeito demonstrado e escopo autorizado.
- **Critérios de aceite:** todos os CA de `spec.md` ligados a testes/evidências; suíte acordada passa com contagens reais; limitações de transporte/ambiente/concorrência permanecem explícitas; nenhuma tarefa ou iniciativa é concluída sem evidência.
- **Testes previstos:** testes unitários de serviço, testes MVC/JWT, testes PostgreSQL/Testcontainers, regressão de auth/pedidos e `mvn clean test`.
- **Limites:** não iniciar outra iniciativa nem corrigir limitações de 001–004 sem decisão e autorização próprias.

## Matriz inicial de critérios e tarefas

| Critérios | Tarefas principais |
|---|---|
| CA-01 a CA-05 (autorização/identidade/alvos/IDs) | T-01, T-03, T-05 |
| CA-06 a CA-09a (CREATE/CHANGE/REMOVE/no-op) | T-01, T-02, T-04, T-05 |
| CA-10 e CA-11 (justificativa/entrada confiável) | T-01, T-02, T-03, T-05 |
| CA-12 a CA-14 (atomicidade/concorrência/append-only) | T-01, T-04, T-05 |
| CA-15 e CA-16 (autorização de pedidos/contrato HTTP) | T-02, T-03, T-05 |

Nenhum teste foi executado para esta iniciativa durante o planejamento. Nenhuma tarefa de implementação está aprovada ou concluída.
