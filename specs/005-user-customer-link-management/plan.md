# Plano técnico: Administração de vínculo User–Customer

**Iniciativa:** `005-user-customer-link-management`  
**Estado:** proposta documental revisada; recomendações de contrato aguardam aprovação da iniciativa  
**Base:** inspeção documental e de código em 2026-10-22. A implementação de serviço e os testes unitários da T-01 foram executados em 2026-10-22; os resultados e limites estão em `tasks.md`.

## 1. Estado atual confirmado

- `User` contém `UUID customerId` nullable. `V4__add_user_customer_link_and_audit.sql` cria a coluna com FK e índice não único, sem backfill automático.
- A mesma migration cria a tabela de auditoria com FKs para ator/alvo, snapshots de Customer, ação limitada a `CREATE`/`CHANGE`/`REMOVE`, instante e justificativa `TEXT`, e trigger append-only.
- `UserCustomerLinkAudit` instancia eventos e define instante UTC se ausente; `UserCustomerLinkAuditRepository` só oferece operações padrão de persistência.
- `UserRepository` inclui `findByIdForUpdate` com `PESSIMISTIC_WRITE`, adicionado na T-01 para serializar alterações ao mesmo alvo; `User` não possui `@Version`. A eficácia PostgreSQL ainda aguarda comprovação na T-04.
- `CustomerRepository` oferece existência por e-mail/documento e operações JpaRepository; pode buscar Customer por UUID via `findById` padrão.
- `AuthenticatedUserResolver` resolve o principal JWT para User persistido, mas `AuthenticatedUserIdentity` expõe somente `userId` e `customerId`, não `Role`.
- `SecurityConfig` usa JWT e `@EnableMethodSecurity`; as rotas de domínio autenticadas não têm um namespace administrativo existente. `CustomerController.create` mostra o padrão `@PreAuthorize("hasRole('ADMIN')")`.
- `GlobalExceptionHandler` já produz `ApiError` para validação (400), recurso não encontrado (404), desserialização (400) e falha inesperada (500 genérico). Não há conflito específico de associação.
- Testcontainers/PostgreSQL já estão disponíveis. `PostgresContainerIntegrationTest` comprova identidade JWT persistida e append-only da auditoria, mas não fluxo administrativo de vínculo.

## 2. Estratégia proposta

1. A proposta contratual revisada recomenda `PUT /admin/users/{userId}/customer-link` para associar/trocar e `POST /admin/users/{userId}/customer-link/removal` para remover, ambas com `200` e DTO de resultado. A recomendação evita corpo em `DELETE`, não utilizado pelos contratos/testes existentes, e mantém justificativa no corpo.
2. Criar um serviço de domínio no módulo auth, por exemplo `UserCustomerLinkService`, evitando acoplar controller a repositórios. Aplicar `@Transactional` à operação completa.
3. Resolver o ator por `AuthenticatedUserResolver.resolveCurrent()` e verificar no banco o papel atual `Role.ADMIN`; não confiar exclusivamente na claim JWT. Uma opção é consultar `UserRepository.findById(identity.userId())` e validar o papel persistido dentro da transação. Se necessário para evitar dupla consulta, avaliar ampliar a identidade resolvida de forma compatível, sem mudar o JWT.
4. Carregar/bloquear o alvo antes de ler o vínculo anterior, verificar que seu papel persistido é `USER`, confirmar a existência do Customer destino e calcular ação/no-op a partir do estado bloqueado.
5. Atualizar `target.customerId` e inserir `UserCustomerLinkAudit` com ator, alvo, valores anterior/novo, ação e justificativa normalizada. Fazer flush dentro da transação para tornar falha de auditoria detectável antes de concluir a operação; rollback deve cobrir ambos os writes.
6. A T-01 adicionou `UserRepository.findByIdForUpdate` com `@Lock(PESSIMISTIC_WRITE)` sobre a linha do alvo. O método é invocado dentro do serviço `@Transactional`; sua espera/serialização real no PostgreSQL e possíveis interações de contenção ainda precisam ser confirmadas em Testcontainers na T-04.
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
| `customerId=NULL` | Remover | Recomendação: sem mutação | Recomendação: `200 changed=false`, sem evento; depende de aprovação |

Tentativa de alvo ADMIN é bloqueada antes de atualizar; recomenda-se `400 ApiError` por reutilizar a semântica existente para entrada/tipo inválido via `IllegalArgumentException`. Ator persistido que não seja ADMIN recebe rejeição de autorização. IDs inexistentes devem usar `ResourceNotFoundException` e o 404 existente; mensagens específicas serão definidas antes da implementação.

## 4. Contrato HTTP em avaliação

Recomendações contratuais documentadas em `spec.md` (sem precedente equivalente para update/delete):

- `PUT /admin/users/{userId}/customer-link`, JSON com `customerId` e `justification`, para associação/troca e repetição sem efeito.
- `POST /admin/users/{userId}/customer-link/removal`, JSON com `justification`, para remoção sem corpo em DELETE.
- Sucesso recomendado `200` com DTO pequeno de `userId`, `customerId` nullable e `changed`.
- Alvo ADMIN recomendado como `400 ApiError`; remoção sem vínculo recomendada como `200 changed=false` sem auditoria.
- Justificativa: aplicar `String.strip()`, validar obrigatoriedade e comprimento máximo de 500 após strip e persistir o valor stripped, preservando espaços internos. DTOs existentes demonstram uso de `@NotBlank`/`@Size`, mas não normalização persistida; validação de tamanho no texto bruto não pode rejeitar uma justificativa que passe do limite bruto apenas por espaços de borda removidos pelo strip.

Respostas definidas pelos handlers existentes: não autenticado `401`, não ADMIN `403`, falha de validação/desserialização `400`, IDs inexistentes `404`, persistência inesperada `500` genérico. A aprovação deve confirmar as recomendações sem precedente antes da implementação da T-02. Não mapear globalmente `IllegalStateException` a conflito.

## 5. Estratégia de testes

- **Unitários de serviço:** novo vínculo, troca, remoção, repetição sem auditoria, usuário inexistente, alvo ADMIN, Customer inexistente, ator persistido com papel diferente da claim, e justificativa após normalização.
- **MVC/segurança:** JWT real para ADMIN, USER autenticado, anônimo e token cujo papel ADMIN não corresponde ao papel persistido; verificar status, DTO e ausência de campos sensíveis.
- **PostgreSQL/Testcontainers:** persistir vínculo/auditoria; conferir ator e snapshots; invalidar atualização de Customer não existente; provocar falha de INSERT de auditoria e confirmar rollback do vínculo; confirmar `@Lock` efetivo e sequência das alterações concorrentes no mesmo User.
- **Regressão de autorização:** após associar, USER só acessa pedidos daquele Customer; após troca, novo escopo vale; após remover, USER recebe o comportamento existente de sem vínculo (403). Validar por JWT e entidades persistidas.
- **Regressão de contrato:** executar os testes existentes de auth, customer, order, handler e PostgreSQL; não reabrir resultados/decisões das iniciativas 001–004 sem evidência de defeito.
- Não declarar cobertura por socket HTTP se o teste for MockMvc. Usar Testcontainers para alegações de lock/rollback no PostgreSQL.

## 6. Arquivos e componentes prováveis

- `auth/controller`: novo controller administrativo de vínculo; não há controller de User existente.
- `auth/service`: novo serviço transacional; `AuthenticatedUserResolver`/`AuthenticatedUserIdentity` para identidade persistida.
- `auth/dto`: requests/responses novos, sem senha ou `actorUserId` na entrada.
- `auth/repository/UserRepository.java`: `findByIdForUpdate` usa lock pessimista; eficácia no banco aguarda T-04.
- `customer/repository/CustomerRepository.java`: lookup do Customer de destino.
- `auth/entity/UserCustomerLinkAudit.java`, `UserCustomerLinkAction.java` e `UserCustomerLinkAuditRepository.java`: componentes existentes a reutilizar.
- Testes: `AuthenticatedUserResolverTest`, `PostgresContainerIntegrationTest` e novas classes focadas `UserCustomerLinkServiceTest`, `UserCustomerLinkControllerIntegrationTest` e/ou `UserCustomerLinkPostgresIntegrationTest`, seguindo decisões finais de organização.
- `GlobalExceptionHandler.java`: somente se a decisão aprovada requerer exceção específica nova; sem mapeamentos globais amplos.
- `V4__add_user_customer_link_and_audit.sql`: somente leitura nesta fase; migration nova não prevista.

## 7. Riscos e mitigação

- **Concessão indevida de acesso:** atualizar o Customer permite ao alvo USER acessar pedidos daquele cliente. Exigir método ADMIN e papel persistido ADMIN, testar JWT real e provar alteração de escopo.
- **Claim desatualizada:** `SecurityConfig` usa autoridade do JWT. A validação persistida no serviço impede que role no token, isoladamente, autorize a mutação; decidir como reportar ator ausente/desativado.
- **Concorrência no vínculo:** `User` não tem `@Version`; lock pessimista do alvo é a opção inicial. Confirmar no PostgreSQL e testar duas alterações simultâneas.
- **Auditoria fora de sincronia:** inserir no mesmo transaction scope e testar rollback por falha real de INSERT/flush.
- **Migração insuficiente:** V4 contém estrutura base, mas validar constraints/limites reais antes de concluir que nenhuma migration será necessária.
- **Justificativa auditável:** texto pode conter dado pessoal; normalizar, limitar a 500 e não devolver/publicar em endpoint.
- **Decisões de negócio sem precedente:** sucesso de update/delete, operação de remoção sem vínculo e status de alvo ADMIN são recomendações explícitas em `spec.md` e dependem de aprovação. `POST` para remoção evita a necessidade de corpo em DELETE.

## 8. Dependências e sequência

A migration V4, a auditoria append-only, `AuthenticatedUserResolver`, `UserRepository`, `CustomerRepository`, `@PreAuthorize` e o contrato `ApiError` existente são dependências locais. Nenhuma mudança de arquitetura ou infraestrutura é necessária no estado atual observado.

Sequência: aprovação da iniciativa/contratos recomendados → T-01 regras e serviço → T-02 API REST → T-03 autorização persistida e matriz JWT → T-04 atomicidade/concorrência PostgreSQL → T-05 regressão final. Na T-01, os contratos aprovados devem ser registrados sem reabrir convenções já sustentadas pelo código.

**Status:** iniciativa ainda proposta; T-01 executada e documentada em `tasks.md`. T-02 a T-05 não iniciadas. Concorrência e rollback reais no PostgreSQL permanecem sem comprovação.
