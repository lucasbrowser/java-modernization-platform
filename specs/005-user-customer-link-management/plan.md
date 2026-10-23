# Plano técnico: Administração de vínculo User–Customer

**Iniciativa:** `005-user-customer-link-management`  
**Estado:** T-01 a T-04 implementadas e verificadas; T-05 permanece incompleta devido à evidência pendente do CA-15. A iniciativa não está declarada finalizada.  
**Base:** decisões e inspeção iniciadas em 2026-10-22; resultados por tarefa e a validação final estão em `tasks.md`.

## 1. Estado atual confirmado

- `User` contém `UUID customerId` nullable. `V4__add_user_customer_link_and_audit.sql` cria a coluna com FK e índice não único, sem backfill automático.
- A mesma migration cria a tabela de auditoria com FKs para ator/alvo, snapshots de Customer, ação limitada a `CREATE`/`CHANGE`/`REMOVE`, instante e justificativa `TEXT`, e trigger append-only.
- `UserCustomerLinkAudit` instancia eventos e define instante UTC se ausente; `UserCustomerLinkAuditRepository` só oferece operações padrão de persistência.
- `UserRepository` inclui `findByIdForUpdate` com `PESSIMISTIC_WRITE`, adicionado na T-01; teste T-04 observou no PostgreSQL uma transação bloqueada e o backend bloqueador. `User` não possui `@Version`.
- `CustomerRepository` oferece existência por e-mail/documento e operações JpaRepository; pode buscar Customer por UUID via `findById` padrão.
- `AuthenticatedUserResolver` resolve o principal JWT para User persistido, mas `AuthenticatedUserIdentity` expõe somente `userId` e `customerId`, não `Role`.
- `SecurityConfig` usa JWT e `@EnableMethodSecurity`; as rotas de domínio autenticadas não têm um namespace administrativo existente. `CustomerController.create` mostra o padrão `@PreAuthorize("hasRole('ADMIN')")`.
- `GlobalExceptionHandler` já produz `ApiError` para validação (400), recurso não encontrado (404), desserialização (400) e falha inesperada (500 genérico). Não há conflito específico de associação.
- Testcontainers/PostgreSQL são usados pelas classes `UserCustomerLinkAuthorizationPostgresIntegrationTest` e `UserCustomerLinkPostgresConsistencyIntegrationTest` para autorização, persistência, rollback e concorrência. `PostgresContainerIntegrationTest` também verifica append-only da auditoria.

## 2. Estratégia implementada

1. O contrato implementado usa `PUT /api/admin/users/{userId}/customer-link` para associar/trocar e `POST /api/admin/users/{userId}/customer-link/removal` para remover, ambas com `200` e DTO de resultado, mantendo justificativa no corpo.
2. Criar um serviço de domínio no módulo auth, por exemplo `UserCustomerLinkService`, evitando acoplar controller a repositórios. Aplicar `@Transactional` à operação completa.
3. Resolver o ator por `AuthenticatedUserResolver.resolveCurrent()` e verificar no banco o papel atual `Role.ADMIN`; não confiar exclusivamente na claim JWT. Uma opção é consultar `UserRepository.findById(identity.userId())` e validar o papel persistido dentro da transação. Se necessário para evitar dupla consulta, avaliar ampliar a identidade resolvida de forma compatível, sem mudar o JWT.
4. Carregar/bloquear o alvo antes de ler o vínculo anterior, verificar que seu papel persistido é `USER`, confirmar a existência do Customer destino e calcular ação/no-op a partir do estado bloqueado.
5. Atualizar `target.customerId` e inserir `UserCustomerLinkAudit` com ator, alvo, valores anterior/novo, ação e justificativa normalizada. Fazer flush dentro da transação para tornar falha de auditoria detectável antes de concluir a operação; rollback deve cobrir ambos os writes.
6. A T-01 adicionou `UserRepository.findByIdForUpdate` com `@Lock(PESSIMISTIC_WRITE)` sobre a linha do alvo. A T-04 confirmou em Testcontainers a espera no PostgreSQL, identificando o backend bloqueado e o bloqueador em `pg_stat_activity`/`pg_blocking_pids`.
7. Expor controlador específico, preferencialmente no pacote auth, com métodos REST escolhidos na T-01 e `@PreAuthorize("hasRole('ADMIN')")`. A verificação persistida do ADMIN permanece no serviço mesmo com preautorização.
8. Reutilizar DTOs específicos de entrada/saída e `ApiError`; não reutilizar DTO de User que possa expor senha ou detalhes desnecessários. Nenhuma alteração em `SecurityConfig` é presumida.
9. Antes de propor migration, verificar novamente V4 contra todos os campos/regras. A estrutura observada comporta as decisões atuais; nenhum novo campo é identificado nesta inspeção.

## 3. Transições e persistência planejadas

| Estado anterior do alvo USER | Solicitação | Escrita do vínculo | Auditoria |
|---|---|---|---|
| `customerId=NULL` | Customer existente diferente de null | Define novo Customer | `CREATE`, previous null, new informado |
| Customer A | Customer B (B diferente de A) | Troca para B | `CHANGE`, previous A, new B |
| Customer A | Repetir A | Sem alteração | Nenhum evento, decisão já aprovada |
| Customer A | Remover | Define null | `REMOVE`, previous A, new null |
| `customerId=NULL` | Remover | Sem mutação | `200 changed=false`, sem evento |

Tentativa de alvo ADMIN é bloqueada antes de atualizar e retorna `400 ApiError` via `IllegalArgumentException`. Ator persistido que não seja ADMIN recebe rejeição de autorização. IDs inexistentes usam `ResourceNotFoundException` e o 404 existente.

## 4. Contrato HTTP implementado

Contrato documentado em `spec.md` e implementado na T-02:

- `PUT /api/admin/users/{userId}/customer-link`, JSON com `customerId` e `justification`, para associação/troca e repetição sem efeito.
- `POST /api/admin/users/{userId}/customer-link/removal`, JSON com `justification`, para remoção sem corpo em DELETE.
- Sucesso `200` com DTO pequeno de `userId`, `customerId` nullable e `changed`.
- Alvo ADMIN retorna `400 ApiError`; remoção sem vínculo retorna `200 changed=false` sem auditoria.
- Justificativa: aplicar `String.strip()`, validar obrigatoriedade e comprimento máximo de 500 após strip e persistir o valor stripped, preservando espaços internos. DTOs existentes demonstram uso de `@NotBlank`/`@Size`, mas não normalização persistida; validação de tamanho no texto bruto não pode rejeitar uma justificativa que passe do limite bruto apenas por espaços de borda removidos pelo strip.

Respostas: anônimo `401`, não ADMIN `403`, validação/desserialização `400`, IDs inexistentes `404`, persistência inesperada `500` genérico. Alvo ADMIN retorna `400`. JWT válido cujo subject não corresponde a usuário persistido foi observado retornando `500` genérico; a política global não foi alterada. Não mapear globalmente `IllegalStateException` a conflito.

## 5. Estratégia de testes

- **Unitários de serviço:** novo vínculo, troca, remoção, repetição sem auditoria, usuário inexistente, alvo ADMIN, Customer inexistente, ator persistido com papel diferente da claim, e justificativa após normalização.
- **MVC/segurança:** JWT assinado, filtro e Spring Security para ADMIN, USER, anônimo e role ADMIN desatualizada; a T-03 também exercita serviço real e PostgreSQL, verificando status, estado e auditoria.
- **PostgreSQL/Testcontainers:** testes T-03/T-04 persistem vínculo/auditoria, conferem ator e snapshots, provocam falha real de INSERT e confirmam rollback, observam o lock efetivo e verificam sequência concorrente. A T-04 também verifica no-ops sem auditoria.
- **Regressão de autorização:** o conjunto atual confirma políticas de pedido para usuários vinculados e comportamento de usuários sem vínculo; ainda falta um cenário que execute associação/troca/remoção pelo fluxo novo e, em seguida, verifique o novo escopo de pedido por JWT. Esta lacuna do CA-15 impede declarar a iniciativa finalizada.
- **Regressão de contrato:** executar os testes existentes de auth, customer, order, handler e PostgreSQL; não reabrir resultados/decisões das iniciativas 001–004 sem evidência de defeito.
- Não declarar cobertura por socket HTTP se o teste for MockMvc. Usar Testcontainers para alegações de lock/rollback no PostgreSQL.

## 6. Arquivos e componentes prováveis

- `auth/controller/AdminUserCustomerLinkController.java`: controller REST administrativo de vínculo.
- `auth/service/UserCustomerLinkService.java`: serviço transacional; `AuthenticatedUserResolver`/`AuthenticatedUserIdentity` fornecem a identidade persistida.
- `auth/dto`: requests/responses específicos, sem senha ou `actorUserId` na entrada.
- `auth/repository/UserRepository.java`: `findByIdForUpdate` usa lock pessimista, cuja espera foi observada no PostgreSQL na T-04.
- `customer/repository/CustomerRepository.java`: lookup do Customer de destino.
- `auth/entity/UserCustomerLinkAudit.java`, `UserCustomerLinkAction.java` e `UserCustomerLinkAuditRepository.java`: componentes existentes a reutilizar.
- Testes: `UserCustomerLinkServiceTest`, `AdminUserCustomerLinkControllerIntegrationTest`, `UserCustomerLinkAuthorizationPostgresIntegrationTest`, `UserCustomerLinkPostgresConsistencyIntegrationTest` e `PostgresContainerIntegrationTest`.
- `GlobalExceptionHandler.java` e migration V4 não foram alterados; o formato de erro e o schema existentes foram reutilizados.

## 7. Riscos e mitigação

- **Concessão indevida de acesso:** T-02/T-03 exercitam `@PreAuthorize`, JWT, papel persistido do ator e alvo USER. A prova do escopo de pedidos após mudança pelo fluxo novo permanece pendente no CA-15.
- **Claim desatualizada:** a T-03 comprovou que a role ADMIN no JWT, após o papel persistido mudar para USER, recebe `403` sem mutação. JWT válido com subject inexistente ainda retorna `500` genérico.
- **Concorrência no vínculo:** `User` não tem `@Version`; T-04 confirmou o lock pessimista no PostgreSQL e snapshots de duas alterações concorrentes.
- **Auditoria fora de sincronia:** T-04 confirmou rollback do vínculo quando o INSERT da auditoria falha no PostgreSQL.
- **Migração:** T-01 a T-04 não exigiram migrations; os testes executaram com o schema Flyway existente.
- **Justificativa auditável:** texto pode conter dado pessoal; normalizar, limitar a 500 e não devolver/publicar em endpoint.
- **Limitação de autorização de pedidos:** a suíte atual não executa pedidos após alteração de vínculo pelo novo fluxo; evidência pendente do CA-15, descrita na seção 9.

## 8. Dependências e sequência

A migration V4, a auditoria append-only, `AuthenticatedUserResolver`, `UserRepository`, `CustomerRepository`, `@PreAuthorize` e o contrato `ApiError` existente são dependências locais. Nenhuma mudança de arquitetura ou infraestrutura é necessária no estado atual observado.

Sequência executada: T-01 serviço → T-02 API REST → T-03 autorização persistida e JWT → T-04 atomicidade/concorrência PostgreSQL → T-05 revisão final. A T-05 executou `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean test`: `BUILD SUCCESS`, 169 testes, 0 falhas, 0 erros e 0 ignorados.

## 9. Resultado e pendência de fechamento

Implementação e contrato HTTP estão consistentes com as decisões consolidadas na T-01 e com os testes T-02 a T-04. Sem evidência direta de requisições de pedido após mudança de vínculo pelo novo fluxo, o CA-15 permanece parcialmente verificado. Como o escopo da T-05 permite apenas mudanças documentais, esta pendência foi registrada em `spec.md` e `tasks.md`; a iniciativa permanece não finalizada até que o cenário seja autorizado e comprovado. Limitações adicionais confirmadas: testes MVC usam `MockMvc`, não socket HTTP; JWT com subject sem usuário persistido resulta em `500` genérico; não existe endpoint de consulta de auditoria, por decisão de escopo.
