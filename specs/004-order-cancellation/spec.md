# Especificação: Cancelamento de pedidos com devolução de estoque

**Iniciativa:** `004-order-cancellation`  
**Estado:** concluída em 2026-10-22 após execução de T-01 a T-05; limitações residuais em `tasks.md`  
**Data do planejamento:** 2026-10-22

## 1. Objetivo

Permitir o cancelamento seguro de pedidos ainda canceláveis, conforme a política existente de autorização, alterando o estado do pedido para `CANCELLED` e devolvendo ao estoque as quantidades reservadas na criação. A operação deve ser transacional e segura diante de repetições e chamadas concorrentes.

## 2. Evidências do estado atual

- `OrderStatus` contém `CREATED`, `CONFIRMED` e `CANCELLED`.
- `OrderController` expõe `POST /orders` e consultas por ID/cliente; não expõe cancelamento.
- `OrderService.create` cria pedidos em `CREATED`, rejeita itens vazios por validação do request, verifica estoque e decrementa `Product.stock` dentro de transação. Repetição da chave idempotente existente retorna o pedido antes de repetir a baixa.
- `OrderItem` armazena quantidade e produto, necessários para calcular a devolução; não há tabela/evento separado de movimentação de estoque.
- `Product.version` usa `@Version`; `OrderConcurrencyIntegrationTest` comprova proteção contra overselling por atualizações concorrentes de estoque.
- `Order` não tem campo `@Version`. As migrations V1–V4 não incluem entidade de cancelamento, auditoria de estoque nem coluna de versão no pedido. A iniciativa não propõe migration sem necessidade comprovada.
- `OrderAuthorizationService.currentScope()` permite ADMIN para todos os clientes, permite USER apenas com Customer associado persistido e lança `AccessDeniedException` sem associação. Leituras de pedido mascaram pedido de terceiro como 404.
- `GlobalExceptionHandler` mapeia recurso inexistente a 404, `IllegalArgumentException` a 400, optimistic locking a 409, repropaga exceções de segurança para o Spring Security e mapeia falhas não previstas para 500 genérico. Não há mapeamento existente para conflito de estado de domínio.
- `ApiError` já fornece timestamp, status, error, message e fields. A nova operação deve reutilizar esse formato.

Essas evidências são a base do comportamento proposto; esta especificação não afirma que a operação de cancelamento já exista.

## 3. Escopo

- Adicionar uma operação autenticada de cancelamento de pedido.
- Aplicar a política de ADMIN/USER e o escopo por Customer já usados para pedidos.
- Cancelar apenas pedidos no estado `CREATED`.
- Tratar `CANCELLED` como repetição concluída sem efeitos adicionais.
- Rejeitar os demais estados, atualmente `CONFIRMED`, como conflito de estado.
- Atualizar status e restaurar estoque de todos os itens na mesma transação.
- Proteger a execução concorrente para que cancelamentos repetidos do mesmo pedido devolvam estoque uma única vez.
- Reutilizar `OrderResponse` e `ApiError`; não acrescentar campos à resposta.
- Acrescentar testes unitários e testes de integração com PostgreSQL/Testcontainers e JWT real onde o cenário exigir validação ponta a ponta da segurança.

## 4. Decisões de negócio consolidadas para o plano

1. ADMIN pode cancelar pedidos elegíveis de qualquer Customer.
2. USER pode cancelar somente pedidos do Customer associado à identidade persistida.
3. USER sem Customer associado recebe 403 Forbidden.
4. USER que tenta cancelar pedido de terceiro recebe 404 Not Found, indistinguível do pedido inexistente pela resposta da API.
5. Cancelar muda o estado para `CANCELLED` e soma ao estoque de cada produto a quantidade registrada em cada item.
6. Repetir o cancelamento de pedido já cancelado retorna sucesso sem devolver estoque novamente.
7. Somente `CREATED` é cancelável. Pedidos em outros estados (atualmente `CONFIRMED`) geram conflito sem alterar pedido ou estoque.
8. Mudança de estado e devolução de estoque são atômicas em uma transação. Falha de persistência reverte todas as alterações.
9. Chamadas concorrentes de cancelamento do mesmo pedido restauram o estoque exatamente uma vez.
10. Reutilizar autorização, `ApiError`, transações JPA e controle otimista existente em `Product.version`; o planejamento técnico propõe também serializar a decisão do pedido com lock pessimista da linha do pedido, sem migration. Esta escolha localiza a proteção de uma única transição de estado; o versionamento do produto continua protegendo atualizações de estoque concorrentes com outras operações.
11. Não adicionar pagamento, reembolso financeiro, nova role ou integração externa.
12. Um pedido `CREATED` sem itens (possível por gravação legada/direta, pois o banco não impõe mínimo de itens) pode ser cancelado: não há quantidade conhecida para devolver, portanto o pedido passa a `CANCELLED` sem alteração de estoque. Não tentar inferir ou fabricar uma devolução.

### Ajuste mínimo justificado pela evidência

O handler atual associa 409 a falha de optimistic locking, mas não existe exceção de conflito para estado de domínio. Mapear `IllegalStateException` para 409 seria amplo e poderia converter falhas não relacionadas. O plano propõe uma exceção de domínio específica de cancelamento e um mapeamento específico para `ApiError` 409, mantendo inalterados os mapeamentos existentes. Isso é uma extensão necessária para a nova operação, não uma alteração dos contratos existentes.

## 5. Contrato HTTP proposto

Os caminhos abaixo são relativos ao context path `/api` configurado para a API principal. A rota proposta é `POST /orders/{id}/cancel`, sem corpo. Em sucesso, retorna HTTP 200 e o `OrderResponse` já existente com `status=CANCELLED`.

| Cenário | HTTP | Corpo/efeito |
|---|---:|---|
| Cancelamento de pedido `CREATED` autorizado | 200 | `OrderResponse` existente com estado `CANCELLED`; estoque restaurado uma vez. |
| Pedido inexistente | 404 | `ApiError` existente; mensagem neutra `Order not found`. Sem alteração. |
| Pedido de terceiro por USER | 404 | Mesmo status e mensagem de pedido inexistente; não revelar existência. Sem alteração. |
| USER sem Customer associado | 403 | Tratamento atual do Spring Security; sem consulta/mutação de pedido. |
| Pedido em estado não cancelável (atualmente `CONFIRMED`) | 409 | `ApiError` com erro `Conflict` e mensagem fixa neutra de estado não cancelável. Sem alteração de pedido/estoque. |
| Repetição para pedido `CANCELLED` | 200 | Mesmo recurso em estado `CANCELLED`; nenhuma devolução adicional. |
| Requisição não autenticada | 401 | Rejeição pela cadeia de segurança existente. |
| ID malformado ou falha de validação/conversão da rota | 400 | Preservar o resolver MVC e o contrato de erro atual; testar status e forma efetivamente produzidos, pois `TypeMismatchException` é repropagada pelo advice atual. |
| Falha de integridade/persistência inesperada | 500 | `ApiError` genérico do contrato existente; transação revertida. Falha de optimistic locking conserva o 409 existente. |

A mensagem exata do novo conflito de domínio deve ser constante e não conter dados internos. Não adicionar campos a `OrderResponse` ou `ApiError`. O pedido inexistente e o pedido de terceiro devem compartilhar a mesma mensagem sem identificador. Falhas normais de validação são limitadas ao path id, já que a operação não recebe corpo.

## 6. Critérios de aceitação

- **CA-01 — Autorização USER:** USER associado consegue cancelar pedido próprio em `CREATED`.
- **CA-02 — Autorização ADMIN:** ADMIN consegue cancelar pedido elegível de qualquer Customer.
- **CA-03 — Isolamento:** USER não cancela pedido de terceiro e recebe 404 igual ao pedido inexistente.
- **CA-04 — Sem vínculo:** USER sem Customer associado recebe 403 e não altera pedido/estoque.
- **CA-05 — Requisição anônima:** chamada sem autenticação recebe 401 pelo mecanismo existente.
- **CA-06 — Estado:** cancelamento persiste `CANCELLED` e resposta usa o `OrderResponse` atual.
- **CA-07 — Estoque:** cada produto envolvido recebe de volta exatamente a soma das quantidades de seus itens no pedido; outros produtos não mudam.
- **CA-07a — Pedido vazio preexistente:** pedido `CREATED` sem itens, se encontrado, é cancelado sem alterar estoque; o comportamento não cria itens nem infere estoque consumido.
- **CA-08 — Repetição:** cancelar novamente um pedido `CANCELLED` é sucesso sem alteração adicional de estoque.
- **CA-09 — Estado não cancelável:** qualquer estado diferente de `CREATED`/`CANCELLED` resulta em 409 sem mutação de pedido ou estoque.
- **CA-10 — Atomicidade:** falha durante a gravação do status ou de qualquer produto deixa todos os valores persistidos como antes da operação.
- **CA-11 — Concorrência:** duas ou mais chamadas concorrentes para o mesmo pedido produzem estado `CANCELLED` e uma única devolução de estoque.
- **CA-12 — Regressão:** autorização de leitura/criação de pedidos, criação, baixa e overselling, replay de idempotência, versionamento de Product e contrato `ApiError` permanecem funcionando.
- **CA-13 — Integração:** testes de persistência/transação/concorrência usam PostgreSQL via Testcontainers; os cenários de segurança exercitam JWT e a identidade persistida quando apropriado.
- **CA-14 — Limite de contrato:** sem alteração nos contratos de endpoints existentes nem campos novos nas respostas existentes.

## 7. Fora do escopo

- Confirmar pedido, transições além de cancelamento e gestão geral do workflow.
- Pagamentos, estornos financeiros, emissão de eventos externos, notificação e auditoria de negócio nova.
- Cancelar pedidos `CONFIRMED` ou outros estados não `CREATED`.
- Expiração automática, prazo de cancelamento ou política de taxas.
- Alterar a criação/idempotência existente salvo correção demonstrada necessária para manter invariantes.
- Migrations; somente incluir uma se o planejamento detalhado provar que a solução aprovada não pode cumprir os critérios sem ela e aguardar autorização para essa mudança de escopo.
- Refatorar amplamente `GlobalExceptionHandler`, segurança ou outros módulos.

## 8. Riscos e pontos técnicos a verificar na implementação

- `Order` não tem versão otimista. O lock de escrita proposto no carregamento do pedido deve ser testado com PostgreSQL para garantir que uma segunda transação leia o estado já cancelado após aguardar a primeira.
- `Product.version` pode gerar conflito durante uma devolução simultânea a criação ou a outro ajuste de estoque. O contrato atual mapeia optimistic locking a 409; a transação de cancelamento deve reverter integralmente nesse caso.
- Estoque é `Integer` e a coluna possui `CHECK (stock >= 0)`, mas o limite máximo Java é menor que o máximo SQL. Overflow aritmético deve ser tratado como falha que reverte, sem permitir valor negativo/silencioso.
- As migrations não obrigam uma linha de item por pedido, embora `OrderRequest.items` seja `@NotEmpty`. Para eventual pedido legado vazio, a regra aprovada neste planejamento é cancelar sem ajuste de estoque; não se pode afirmar que o banco sozinho garante o mínimo de itens.
- `TypeMismatchException` é repropagada pelo advice. A resposta para ID malformado deve ser caracterizada no mesmo estilo do MVC antes de afirmar a forma do corpo.
- Deadlocks/conflitos em pedidos com vários produtos devem ser observados em testes; se a atualização de produtos exigir ordem estável, ordenar os IDs de produto para a escrita, preservando o mapeamento de itens da resposta.

## 9. Referências de implementação existentes

- `OrderController`, `OrderService`, `OrderRepository`, `Order`, `OrderItem`, `OrderStatus`.
- `OrderAuthorizationService` e `AuthenticatedUserResolver`.
- `Product`, `ProductRepository` e `ProductOptimisticLockIntegrationTest`.
- `GlobalExceptionHandler` e `ApiError`.
- `OrderServiceTest`, `OrderControllerIntegrationTest`, `OrderConcurrencyIntegrationTest`, `PostgresContainerIntegrationTest`.

## 10. Estado

Iniciativa implementada e validada pela T-05 em 2026-10-22. A rastreabilidade dos critérios, os resultados da suíte e as limitações observadas estão registrados em `tasks.md`. Permanecem sem comprovação a concorrência do cancelamento com uma operação independente sobre o mesmo produto e os comportamentos já observados para JWT sem usuário persistido e UUID malformado; esses fatos não alteram os critérios funcionais atendidos nem foram modificados nesta tarefa.
