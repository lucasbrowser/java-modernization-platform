# Tarefas: Política de autorização de clientes e pedidos

Esta iniciativa segue o fluxo SDD de `specs/README.md`. As tarefas de implementação abaixo são propostas; listá-las não autoriza sua execução. Cada tarefa requer aprovação explícita antes de começar.

## T-01 — Consolidar a política de autorização

- **Dependências:** nenhuma.
- **Escopo:** consolidar comportamento observado, decisões funcionais e critérios de aceitação em `spec.md`, `plan.md` e `tasks.md`.
- **Critérios de aceitação:** documentar política de clientes e pedidos, cardinalidade User–Customer, ausência de vínculo automático por e-mail, usuários sem vínculo, autorização de propriedade, idempotência e respostas HTTP.
- **Verificação:** coerência entre especificação, plano e tarefas; separar fatos atuais de política aprovada.
- **Status:** aprovada pelo usuário em 2026-10-16; concluída documentalmente. Nenhuma implementação foi realizada nem teste executado.

## T-02 — Implementar associação persistida User–Customer

### T-02.1 — Persistir associação opcional e estrutura de auditoria

- **Dependências:** aprovação explícita deste planejamento; nenhuma tarefa de autorização de pedidos é pré-requisito.
- **Componentes/arquivos:** `V4__add_user_customer_link_and_audit.sql`; `auth.entity.User`; `auth.entity.UserCustomerLinkAudit`; `auth.entity.UserCustomerLinkAction`; `auth.repository.UserCustomerLinkAuditRepository`; `RepositoryIntegrationTest`; `PostgresContainerIntegrationTest`.
- **Escopo:** adicionar `UUID customerId` opcional e escalar em User; FK `users.customer_id -> customers.id`, índice não único e tabela append-only de auditoria com ator, usuário alvo, Customer anterior/novo, ação, instante UTC e justificativa. Não criar operação, endpoint ou tela de associação.
- **Critérios de aceitação:** usuários existentes preservados com `customer_id NULL`; nenhum backfill por e-mail; FK rejeita Customer inexistente; vários usuários podem referenciar o mesmo Customer; nulo permitido; evento de auditoria persiste e UPDATE/DELETE são bloqueados no banco.
- **Testes executados:** migration V3→V4 em schema isolado com usuário e Customer legados de e-mail igual; FK para Customer inexistente; vários usuários para o mesmo Customer; vínculo nulo e ausência de associação automática por e-mail; gravação dos campos auditáveis e rejeição de UPDATE/DELETE. Testes de integração PostgreSQL via Testcontainers.
- **Status:** concluída nesta execução. `mvn clean test`: 101 testes, 0 falhas, 0 erros, 0 ignorados. A primeira execução encontrou e levou à correção de uma asserção ampla no teste de migration; a suíte final passou.

### T-02.2 — Resolver usuário autenticado mantendo login compatível

- **Dependências:** T-02.1 concluída e aprovada.
- **Componentes/arquivos prováveis:** `UserRepository`, serviço de aplicação que precisa da identidade e testes `auth`/segurança.
- **Escopo:** resolver a entidade User pelo principal e-mail atual e expor o UUID/Customer associado ao fluxo de autorização sem buscar Customer por igualdade de e-mail. Login e registro permanecem válidos para vínculo nulo; subject e role do JWT não mudam.
- **Critérios de aceitação:** usuário sem vínculo autentica; resolução identifica o User persistido; nenhum vínculo é criado por registro ou login; regra de propriedade fica centralizável sem duplicação nos controllers.
- **Testes esperados:** integração de registro/login com vínculo nulo e token compatível; resolução de usuário existente; usuários e Customers com e-mail igual continuam desvinculados.

**Status de T-02:** parcialmente concluída. T-02.1 está implementada e testada; T-02.2 permanece proposta e não iniciada, aguardando aprovação explícita.

## T-03 — Restringir criação de clientes a ADMIN

- **Dependências:** aprovação específica de T-03; pode ser implementada em paralelo à T-02 sem alterar contratos.
- **Componentes/arquivos prováveis:** `CustomerController` ou configuração/autorização no padrão existente; `CustomerControllerIntegrationTest`.
- **Escopo:** somente ADMIN pode usar `POST /customers`; USER e ADMIN autenticados mantêm consultas GET existentes.
- **Critérios de aceitação:** ADMIN cria; USER autenticado recebe rejeição de autorização; anônimo recebe `401`; consultas GET funcionam para USER e ADMIN.
- **Testes esperados:** API para anônimo, USER e ADMIN, incluindo os dois GETs e POST.
- **Status:** proposta; requer aprovação explícita.

## T-04 — Aplicar propriedade e autorização aos pedidos

- **Dependências:** T-02.1 e T-02.2 concluídas; aprovação específica de T-04.
- **Componentes/arquivos prováveis:** `OrderController`, `OrderService`, `OrderRepository`, serviço de resolução/autorização de usuário e testes de pedido/segurança.
- **Escopo:** centralizar política de propriedade e aplicá-la às operações existentes. `USER` sem vínculo recebe `403` ao consultar ou criar; `customerId` divergente em `POST /orders` recebe `403`; pedido de terceiro por `GET /orders/{id}` recebe `404`; cliente divergente em `GET /orders/customer/{customerId}` recebe `404`. ADMIN mantém acesso amplo sujeito às regras do domínio. Autorizar antes de devolver replay de idempotência.
- **Critérios de aceitação:** cumprir exatamente os status acima; nunca substituir silenciosamente `customerId`; não revelar pedido/cliente de terceiro; preservar replays legítimos, estoque, validações e integridade; nenhuma rota nova.
- **Testes esperados:** serviços para associado/sem vínculo/USER/ADMIN; API com JWT real para próprios/terceiros; os quatro casos HTTP; replay autorizado e não autorizado; regressão de estoque e validações. `.with(user(...))` sozinho não cobre o filtro JWT.
- **Status:** proposta; requer aprovação explícita.

## T-05 — Completar verificação integrada da política

- **Dependências:** T-02, T-03 e T-04 concluídas e aprovadas.
- **Componentes/arquivos prováveis:** testes de integração de persistência, autenticação, clientes e pedidos.
- **Escopo:** fechar lacunas de cobertura e comparar comportamento com todos os critérios da especificação, sem remover nem enfraquecer testes existentes.
- **Critérios de aceitação:** matriz anônimo/USER/ADMIN, cardinalidade/FK/migração, ausência de associação por e-mail, quatro resultados HTTP definidos, autorização antes de replay, compatibilidade de login e integridade de domínio.
- **Testes esperados:** execução dos testes pertinentes e da suíte acordada na tarefa aprovada; registrar resultados e limitações.
- **Status:** proposta; requer aprovação explícita.

## Operação futura de associação administrativa

Não está incluída nas tarefas acima. Criar, corrigir ou remover vínculos por operação da aplicação exige especificação e aprovação próprias, definindo ator autorizado, justificativa, auditoria transacional, retenção, acesso ao histórico, política de correção e contrato. Não criar endpoint, tela nem fluxo por inferência.

## Decisões consolidadas e limites

- Persistência recomendada: UUID opcional escalar em User, FK e índice não único; sem relação JPA direta com Customer e sem backfill.
- Auditoria recomendada: tabela append-only transacional; ator identificado pelo UUID persistido resolvido da autenticação.
- HTTP para USER sem vínculo em consulta/criação: `403 Forbidden`.
- HTTP para `customerId` divergente em `POST /orders`: `403 Forbidden`.
- HTTP para Customer divergente em `GET /orders/customer/{customerId}`: `404 Not Found`.
- HTTP para pedido de terceiro em `GET /orders/{id}`: `404 Not Found`, decisão previamente aprovada.
- Primeira tarefa de implementação: **T-02.1**, concluída conforme o escopo acima. A próxima tarefa proposta é **T-02.2**, que aguarda aprovação explícita e não foi iniciada.

Nesta execução, somente T-02.1 foi implementada. Nenhuma tarefa futura foi iniciada.
