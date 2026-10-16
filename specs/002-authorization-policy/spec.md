# Especificação: Política de autorização de clientes e pedidos

## 1. Objetivo

Consolidar o comportamento atual, as decisões de negócio aprovadas e os requisitos de autorização para clientes e pedidos. Esta especificação registra a política e não autoriza, por si só, alterações de código, banco de dados ou contratos da API.

## 2. Escopo

Abrange as operações existentes:

- Clientes: `POST /customers`, `GET /customers` (lista paginada) e `GET /customers/{id}`.
- Pedidos: `POST /orders`, `GET /orders/{id}` e `GET /orders/customer/{customerId}`.
- A relação persistida proposta entre `User` e `Customer`, necessária à autorização por propriedade de pedidos.

Os caminhos acima são mappings dos controllers e, na API principal, recebem o prefixo `/api` configurado como context path. Não foram encontrados endpoints de atualização ou exclusão de clientes ou pedidos; não fazem parte desta política.

## 3. Comportamento atual observado

Os itens desta seção descrevem o código inspecionado e não significam que a política futura já esteja implementada.

- A cadeia principal de `SecurityConfig` exige autenticação para as rotas de clientes e pedidos. O filtro JWT converte a claim `role` em autoridade `ROLE_<perfil>` e usa o e-mail do token como principal.
- `CustomerController` tem POST, GET paginado e GET por ID. Não há restrição por perfil no controller. `CustomerService` cria e consulta clientes.
- `OrderController` tem POST, GET por ID e GET por cliente. Não há restrição por perfil ou propriedade no controller ou serviço.
- `OrderRequest` recebe `customerId`. `OrderService.create` usa esse valor para localizar o cliente.
- `OrderService.create` consulta a chave de idempotência e retorna o pedido existente antes de carregar o cliente enviado na requisição ou aplicar verificações de propriedade. A chave está persistida com índice único para valores não nulos.
- `Order` tem relação `ManyToOne` com `Customer`. `User` não tem relação JPA direta com `Customer`; T-02.1 adicionou a referência escalar opcional `customerId`, protegida por FK, sem preenchimento automático. A migration V4 também criou a estrutura de auditoria do vínculo.
- Os testes existentes de clientes e pedidos usam autenticação simulada `.with(user(...))` nos cenários autenticados. Isso não comprova o percurso do filtro JWT para esses controllers.

## 4. Decisões de negócio aprovadas

### 4.1 Clientes

- `USER` e `ADMIN` autenticados podem consultar a lista paginada de clientes e consultar um cliente por ID.
- Somente `ADMIN` pode criar clientes por `POST /customers`.
- Não criar regras para operações de alteração ou exclusão que não existem.

### 4.2 Associação `User`–`Customer`

- A inspeção não encontrou uma associação adequada existente.
- A política requer uma associação explícita e persistida: cada `User` pode estar associado a no máximo um `Customer`, e um `Customer` pode ter vários usuários associados.
- Não associar usuários e clientes automaticamente pela igualdade de e-mails.
- Usuários sem associação não podem consultar nem criar pedidos até serem associados por processo administrativo autorizado.
- O processo de associação deve ter autorização, rastreabilidade e forma de corrigir vínculos. Esta especificação não cria endpoint, interface ou fluxo específico.

A aprovação da decisão de negócio autoriza documentar a associação, mas não autoriza sua implementação, migration ou processo operacional.

### 4.3 Pedidos

- `USER` consulta somente pedidos do `Customer` associado à sua conta, tanto em `GET /orders/{id}` quanto em `GET /orders/customer/{customerId}`.
- `USER` não pode criar pedido para cliente diferente do associado. O `customerId` do corpo não prova autorização; se divergir, a requisição deve ser rejeitada, sem substituir silenciosamente o cliente.
- `ADMIN` pode consultar e administrar pedidos de todos os clientes, preservando validações e integridade do domínio.
- Não criar regras para operações de alteração ou exclusão que não existem.

### 4.4 Respostas e idempotência

#### Decisão aprovada

- Se `USER` tentar consultar um pedido de outro cliente por `GET /orders/{id}`, responder `404 Not Found`, igualando a resposta ao caso de pedido inexistente e sem revelar se o pedido existe.
- Verificar autorização antes de devolver um pedido existente por chave de idempotência. Um replay não pode expor pedido ao qual o usuário não tem acesso.
- Preservar o comportamento legítimo de idempotência, validações do domínio e regras de estoque.

#### Decisões HTTP aprovadas

- Usuário sem Customer associado que tentar consultar ou criar pedido recebe `403 Forbidden`.
- `USER` que enviar `customerId` divergente no `POST /orders` recebe `403 Forbidden`; o cliente não é substituído silenciosamente.
- `USER` que solicitar em `GET /orders/customer/{customerId}` um Customer diferente do associado recebe `404 Not Found`, sem revelar existência ou associação de outro cliente.
- `USER` que consultar pedido de terceiro por `GET /orders/{id}` recebe `404 Not Found`, igualando a resposta ao pedido inexistente.

Esses status HTTP são decisões funcionais aprovadas e devem ser mantidos nos critérios e testes. A especificação não autoriza criar rotas administrativas para gerenciar vínculos.

## 5. Matriz da política

Os caminhos são mappings relativos ao context path `/api` na API principal.

| Operação existente | Política aprovada | Observação sobre implementação atual |
|---|---|---|
| `GET /customers` | `USER` e `ADMIN` autenticados | Atualmente autenticação geral, sem restrição por perfil. |
| `GET /customers/{id}` | `USER` e `ADMIN` autenticados | Atualmente autenticação geral, sem restrição por perfil. |
| `POST /customers` | Somente `ADMIN` | Atualmente não há restrição por perfil no controller; a política aprovada ainda precisa ser implementada. |
| `GET /orders/{id}` | `USER` somente se o pedido pertencer ao cliente associado; `ADMIN` pode consultar todos. Para pedido de terceiro por `USER`, HTTP 404. | Atualmente qualquer usuário autenticado pode chegar à consulta; não há filtro de propriedade. |
| `GET /orders/customer/{customerId}` | `USER` somente para o cliente associado; `ADMIN` pode consultar todos. | Atualmente consulta o ID solicitado sem verificar o usuário autenticado. |
| `POST /orders` | `USER` somente para o cliente associado e `ADMIN` para todos os clientes, sujeito às validações do domínio. | Atualmente o serviço confia no `customerId` do corpo e não aplica propriedade. |

A política não concede acesso anônimo. As respostas de autorização para usuários sem vínculo ou que enviem/solicitem outro Customer estão definidas na seção 4.4.

## 6. Critérios de aceitação

Os critérios abaixo devem ser verificados quando as tarefas de implementação forem aprovadas e executadas. Não representam evidência de que o comportamento já existe.

- **CA-01 — Autenticação:** requisições anônimas às operações protegidas existentes de clientes e pedidos são rejeitadas com `401`; nenhuma rota nova é criada.
- **CA-02 — Consulta de clientes:** `USER` e `ADMIN` autenticados conseguem consultar a lista paginada e um cliente por ID.
- **CA-03 — Criação de cliente:** `ADMIN` consegue criar cliente; `USER` autenticado é rejeitado em `POST /customers`; anônimo é rejeitado por autenticação.
- **CA-04 — Associação:** um usuário pode ter no máximo um cliente associado e um cliente pode ter vários usuários. Usuários antigos podem permanecer sem associação; nenhum vínculo surge por igualdade de e-mail.
- **CA-05 — Pedidos próprios:** `USER` associado consulta pedidos próprios por ID e pela rota por cliente.
- **CA-06 — Pedido de terceiro por ID:** quando `USER` consulta por `GET /orders/{id}` pedido de outro cliente, retorna `404 Not Found`, igual à resposta para pedido inexistente, sem revelar se o registro existe.
- **CA-07 — Cliente divergente na criação:** `USER` não cria pedido se o `customerId` do corpo divergir do associado; retorna `403 Forbidden` e não substitui o cliente silenciosamente.
- **CA-08 — Usuário sem vínculo:** usuário sem `Customer` associado não consulta nem cria pedidos e recebe `403 Forbidden`. Não há associação automática por e-mail.
- **CA-08a — Consulta por cliente divergente:** `USER` que solicita `GET /orders/customer/{customerId}` para cliente diferente do associado recebe `404 Not Found`, sem revelar existência ou associação.
- **CA-09 — Acesso amplo de ADMIN:** `ADMIN` consulta e administra pedidos de qualquer cliente nas operações existentes, mantendo as validações de domínio e integridade.
- **CA-10 — Replays idempotentes:** autorização é verificada antes de retornar pedido existente por chave; replay não revela pedido a usuário sem autorização e não duplica efeitos válidos de estoque ou criação.
- **CA-11 — Limite de operações:** a política e os testes cobrem apenas POST/GET existentes. Não são inventadas operações de alteração ou exclusão.

## 7. Decisões técnicas para implementação

O plano técnico recomenda UUID Customer opcional e escalar em User, com FK e índice não único; resolução do User pelo principal JWT atual; e auditoria append-only transacional para uma futura operação de vínculo. Essas escolhas orientam o planejamento, mas não autorizam por si só implementação, migration ou operação administrativa. A sequência de tarefas e os detalhes estão em `plan.md` e `tasks.md`.

Não há status HTTP pendente para os quatro casos da seção 4.4. A autorização de uma futura operação para criar, corrigir ou remover vínculos, incluindo ator, retenção e contrato, requer especificação própria.

## 8. Riscos e limitações

- Consultas amplas de clientes expõem os campos atualmente devolvidos pelo DTO, incluindo e-mail e documento; a decisão aprovada permite consulta ampla, mas não especifica filtragem de campos.
- A autorização por propriedade depende da associação persistida; a estrutura está implementada, mas as verificações de autorização pertencem a tarefas futuras.
- Usuários existentes não têm associação. Não há base documental para associá-los a clientes por e-mail ou outra inferência automática.
- A chave de idempotência é globalmente única e o fluxo atual retorna resultado antes de verificar propriedade; reordenar a validação sem definir a semântica dos replays pode afetar compatibilidade.
- Os testes MVC atuais de clientes e pedidos simulam autenticação; serão necessários testes que cubram identidade/role e associação no caminho de segurança pertinente.

## 9. Testes necessários para implementação futura

- **Unitários de serviço:** usuário associado e sem associação; pedidos próprios e de terceiro; validação do cliente no POST; ADMIN amplo; autorização em replays; preservação de validações e estoque.
- **Persistência/integração:** cardinalidade e integridade da associação; vários usuários para o mesmo cliente; um usuário associado a no máximo um cliente; usuários antigos sem vínculo; ausência de associação automática por e-mail; filtros de pedidos por cliente.
- **Segurança/API:** anônimo, USER e ADMIN em todas as rotas existentes; acesso próprio/terceiro por ID e por cliente; adulteração de `customerId`; usuário sem vínculo; status HTTP aprovado; autorização em replay. Incluir caminho que valide o JWT, pois `.with(user(...))` simula a autenticação.
- **Clientes:** consulta por USER e ADMIN e criação por ADMIN; rejeição de USER no POST.

## 10. Fora de escopo

- Implementar tarefas sem a aprovação explícita correspondente.
- Implementar autorização de pedidos ou gestão operacional de vínculos nesta especificação.
- Criar endpoint, interface ou fluxo específico para associação.
- Alterar contratos da API nesta tarefa.
- Criar operações de atualização ou exclusão.
- Iniciar tarefas posteriores sem nova aprovação explícita.
