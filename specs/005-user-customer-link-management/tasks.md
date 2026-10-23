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

- **Estado:** concluída em 2026-10-23 para a matriz JWT, autorização persistida do ator e auditoria das operações; não implica conclusão da iniciativa.
- **Objetivo:** provar autorização ADMIN pelo filtro JWT e a verificação adicional do papel persistido de ator e alvo.
- **Arquivos/componentes prováveis:** novo controller/service de T-01/T-02; `AuthenticatedUserResolver`; testes de integração de vínculo/JWT, preferencialmente em classe dedicada ou `PostgresContainerIntegrationTest`.
- **Critérios de aceite:** anônimo `401`; USER `403`; ADMIN autenticado e persistido pode operar; role ADMIN apenas no JWT, sem ator ADMIN persistido, não altera vínculo/auditoria; alvo persistido ADMIN é rejeitado conforme contrato de T-01; ator de auditoria sempre é o usuário persistido resolvido, não dado do cliente.
- **Testes previstos:** JWT assinado via `JwtService`, filtro e identidade persistida reais; checar estado do vínculo e auditoria em cada caso negado/autorizado.
- **Limites:** não reclassificar o HTTP do JWT sem usuário persistido fora da nova operação; não enfraquecer `SecurityConfig`; não alterar o JWT.
- **Implementação e evidências:** criada `UserCustomerLinkAuthorizationPostgresIntegrationTest`, com `MockMvc`, filtro JWT, serviço real e PostgreSQL/Testcontainers. Os casos exercitam anonimato e USER persistido nos dois endpoints; ADMIN persistido associa, troca e remove, com ator/snapshots/actions conferidos na tabela de auditoria; role ADMIN no JWT com role persistida alterada para USER é recusada nos dois endpoints sem alteração; alvo ADMIN é rejeitado com `400 ApiError`. Para JWT válido com subject sem usuário persistido, ambos endpoints observaram `500` e a mensagem genérica do handler existente; vínculo e auditoria permaneceram inalterados. A política global não foi alterada.
- **Validação executada:** `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" "-Dtest=UserCustomerLinkAuthorizationPostgresIntegrationTest,AdminUserCustomerLinkControllerIntegrationTest,UserCustomerLinkServiceTest" test` — `BUILD SUCCESS`; 28 testes, 0 falhas, 0 erros, 0 ignorados (5 PostgreSQL/Testcontainers, 10 MVC T-02 e 13 unitários T-01).
- **Limites da evidência:** Testcontainers exerce PostgreSQL e banco real, porém as requisições usam `MockMvc`, não socket HTTP. Não foram alterados filtros, regras ou handlers de produção.

## T-04 — Comprovar persistência, rollback e concorrência

- **Estado:** concluída em 2026-10-23 para atomicidade, lock PostgreSQL, transições concorrentes e operações sem mudança; não implica conclusão da iniciativa.
- **Objetivo:** comprovar no PostgreSQL que vínculo e auditoria são atômicos e que alterações simultâneas no mesmo alvo não geram snapshots incoerentes.
- **Arquivos/componentes prováveis:** `UserRepository` com método de lock se aprovado pela implementação; novo `UserCustomerLinkPostgresIntegrationTest`; Testcontainers existente; trigger/falha de teste isolada sem artefato de produção.
- **Critérios de aceite:** teste PostgreSQL observa lock/serialização da linha de User; falha real ao gravar auditoria reverte `customer_id`; sequência de eventos corresponde à cadeia de valores anterior/novo persistidos; todos os invariantes de FK e append-only permanecem válidos.
- **Testes previstos:** PostgreSQL/Testcontainers com conexões/transações independentes, barreira concorrente e estado final consultado após commit/rollback; nenhuma alegação baseada apenas em mocks.
- **Limites:** não alterar migration aplicada sem autorização específica; não adicionar endpoint de histórico; não executar testes de concorrência de cancelamento da iniciativa 004 nesta tarefa.
- **Implementação e evidências:** criada `UserCustomerLinkPostgresConsistencyIntegrationTest`; não houve alteração de produção ou migration. No teste de rollback, um trigger PostgreSQL temporário e restrito ao `target_user_id` do fixture lança exceção em `BEFORE INSERT` de `user_customer_link_audit`. Ele é criado antes da chamada e removido em `finally`; após o rollback, uma nova transação confirma o Customer original e zero eventos para o alvo. No teste concorrente, duas transações em threads/conexões independentes partem de uma barreira comum e solicitam Customers distintos para o mesmo USER. `pg_stat_activity` mostra uma sessão aguardando evento de lock e `pg_blocking_pids` identifica o backend bloqueador; o teste usa os application names observados para derivar qual alteração ocorreu primeiro. O estado final corresponde à segunda alteração e os snapshots formam a cadeia Customer inicial → primeiro Customer → segundo Customer, com dois eventos `CHANGE`. Nos no-ops, repetir o Customer atual e remover vínculo inexistente preservam estado e inserem zero auditorias para cada alvo.
- **Validação executada:** `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" "-Dtest=UserCustomerLinkPostgresConsistencyIntegrationTest,UserCustomerLinkAuthorizationPostgresIntegrationTest,UserCustomerLinkServiceTest" test` — `BUILD SUCCESS`; 21 testes, 0 falhas, 0 erros, 0 ignorados (3 T-04 PostgreSQL, 5 T-03 PostgreSQL e 13 unitários T-01).
- **Limites da evidência:** concorrência exercitada no PostgreSQL por conexões de transações independentes, coordenadas por barreira e confirmadas pelo estado de lock observado pelo banco. As chamadas ao serviço são diretas dentro de transações Spring reais; os endpoints HTTP/JWT seguem cobertos separadamente pela T-03. O trigger de falha é temporário e criado apenas durante o teste.

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
