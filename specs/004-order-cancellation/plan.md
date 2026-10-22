# Plano técnico: Cancelamento de pedidos com devolução de estoque

**Iniciativa:** `004-order-cancellation`  
**Estado:** proposta, aguardando aprovação  
**Base:** inspeção documental e de código em 2026-10-22; nenhuma implementação/teste executado nesta rodada.

## 1. Estado observado

- O módulo `order` já implementa criação, leitura por ID e listagem por Customer.
- `OrderService.create` usa `@Transactional`, cria `CREATED`, reserva estoque decrementando `Product.stock`, persiste itens com quantidade/preço unitário e chama `EntityManager.flush()`.
- Repetição da chave idempotente retorna o pedido existente antes de consultar/alterar estoque.
- `Product` tem `@Version`; `OrderConcurrencyIntegrationTest` usa PostgreSQL/Testcontainers e duas transações concorrentes para comprovar que estoque não é vendido além do disponível.
- `Order` não tem `@Version`; migrations existentes não incluem estado auxiliar de cancelamento nem trilha de estoque. `OrderItem` não registra se a quantidade já foi devolvida.
- `OrderAuthorizationService` é a fonte comum de escopo: ADMIN amplo; USER associado ao UUID persistido; USER sem associação resulta em `AccessDeniedException`. Leitura de pedido de terceiro resulta em 404.
- `GlobalExceptionHandler` tem `ApiError`, 404 para `ResourceNotFoundException`, 400 para `IllegalArgumentException`, 409 para falhas de optimistic locking, e 500 genérico para outras exceções. Exceções de segurança são repropagadas.
- Testes de serviço cobrem criação, estoque, idempotência e autorização de pedidos. Não foi localizado teste/endpoint de cancelamento.

## 2. Abordagem proposta

1. Acrescentar `POST /orders/{id}/cancel`, relativo ao context path `/api`, com resposta 200 `OrderResponse` existente. Não requer corpo nem novo DTO.
2. No serviço transacional, obter escopo pela implementação existente. Para USER sem vínculo, preservar 403. Carregar pedido por uma consulta de escrita com `PESSIMISTIC_WRITE`; se ausente, 404. Verificar propriedade e mascarar terceiro como 404 com a mesma mensagem do não encontrado.
3. Se já `CANCELLED`, devolver representação atual sem mexer no estoque. Se não for `CREATED`, lançar exceção específica de conflito de cancelamento. Apenas `CREATED` prossegue.
4. Restaurar `quantity` de cada item ao produto correspondente, atualizar status para `CANCELLED` e deixar a transação gravar todas as mudanças conjuntamente. Usar o versionamento atual de `Product` para concorrência com outras atualizações de estoque; conflito de versão desfaz toda a transação.
5. Serializar chamadas ao mesmo pedido por lock pessimista da linha `orders`. A segunda chamada aguarda o commit da primeira e reavalia o status antes de devolver estoque. Teste de integração em PostgreSQL é obrigatório para validar o comportamento do banco/ORM.
6. Adicionar mapeamento específico da exceção de estado não cancelável para 409 `ApiError` com mensagem genérica estável. Não mapear `IllegalStateException` em geral. Preservar os handlers atuais para segurança, 404, validação, optimistic locking e inesperadas.
7. Não mudar schema ou `ApiError`. Confirmar a resposta para path ID inválido e falha de persistência segundo o resolver MVC/handler atual.

### Concorrência e atomicidade

O lock pessimista protege a transição do mesmo pedido, que não possui `@Version`; o `@Version` de Product protege a atualização de estoque contra concorrência com criação de pedidos ou outras escritas. As alterações do pedido e dos produtos serão executadas sob a mesma transação JPA/PostgreSQL. Não capturar nem suprimir falhas após mutação. Qualquer erro no flush/commit deve deixar status e estoques anteriores intactos. Avaliar ordenação estável dos produtos e overflow de `Integer` para reduzir deadlocks e evitar wraparound.

Essa estratégia não depende de uma coluna nova nem de uma marca de devolução separada: o estado do pedido, serializado sob lock, determina se a devolução já foi feita. Isso exige que todo cancelamento passe pelo serviço/endpoint e que testes cubram repetições concorrentes.

## 3. Matriz HTTP resumida

| Caso | Status | Observação de implementação |
|---|---:|---|
| USER associado cancela pedido próprio `CREATED` | 200 | Retorna `OrderResponse` com `CANCELLED`. |
| ADMIN cancela pedido `CREATED` de qualquer cliente | 200 | Mesma resposta existente. |
| Pedido inexistente | 404 | `ResourceNotFoundException`, mensagem neutra `Order not found`. |
| Pedido de terceiro para USER | 404 | Mesma mensagem/resposta do inexistente. |
| USER sem vínculo | 403 | `AccessDeniedException` e tratamento da cadeia de segurança existente. |
| Pedido `CANCELLED` | 200 | Sucesso idempotente; estoque não muda novamente. |
| Estado não cancelável | 409 | Nova exceção específica e handler específico, `ApiError` sem novos campos. |
| Requisição anônima | 401 | Segurança existente. |
| ID inválido no path | 400 | Preservar resolver MVC; caracterizar o formato efetivamente recebido. |
| Falha inesperada de persistência | 500 | Mensagem genérica atual; rollback completo. |
| Optimistic locking de produto | 409 | Handler atual; rollback completo, retry poderá ser tentado pelo cliente. |

## 4. Arquivos prováveis

- `src/main/java/com/github/lucasoliveira/platform/order/controller/OrderController.java` — endpoint de cancelamento.
- `src/main/java/com/github/lucasoliveira/platform/order/service/OrderService.java` — transição, autorização, restauração e transação.
- `src/main/java/com/github/lucasoliveira/platform/order/repository/OrderRepository.java` — consulta com lock de escrita.
- Uma exceção específica no pacote `order` — conflito de estado cancelável.
- `src/main/java/com/github/lucasoliveira/platform/common/exception/GlobalExceptionHandler.java` — mapeamento 409 estreito para a nova exceção; sem alterar os mapeamentos existentes.
- `src/test/java/com/github/lucasoliveira/platform/order/OrderServiceTest.java` — decisões de domínio, autorização delegada, repetição e ausência de alteração em estado não elegível.
- `src/test/java/com/github/lucasoliveira/platform/order/OrderControllerIntegrationTest.java` ou teste dedicado — status, corpo `OrderResponse`/`ApiError` e autenticação.
- Novo teste de integração no módulo order ou extensão de `OrderConcurrencyIntegrationTest` — PostgreSQL/Testcontainers, atomicidade, concorrência e rollback.
- `src/test/java/com/github/lucasoliveira/platform/PostgresContainerIntegrationTest.java` — apenas se for adequado ao padrão existente para integração com JWT persistido; evitar acumular casos extensos numa classe genérica.
- `specs/004-order-cancellation/tasks.md` — resultados de cada tarefa após aprovação/execução.

Não se prevê alteração em migration, `OrderResponse`, `ApiError`, `SecurityConfig`, módulos de auth/customer nem configuração da aplicação.

## 5. Estratégia de testes

- **Unitários de serviço:** CREATED próprio/ADMIN, produto único e múltiplos itens/produtos, soma por produto, já CANCELLED sem salvar produtos, CONFIRMED sem mutação, inexistente, terceiro mascarado e tratamento de falha.
- **MVC/security:** JWT anônimo 401; USER associado próprio; USER terceiro 404; USER sem vínculo 403; ADMIN de outro cliente; estado conflitante 409; repetição 200. Usar `ApiError` atual e resposta `OrderResponse` atual.
- **Integração de persistência:** PostgreSQL via Testcontainers confirma estado e estoque persistidos após sucesso e nenhuma alteração após falha/rollback.
- **Concorrência:** disparar duas chamadas/transações ao mesmo pedido com sincronização concorrente; comprovar ambas as semânticas HTTP previstas, estoque restaurado uma vez e estado final `CANCELLED`. Também verificar conflito de versão se cancelamento concorrer com criação/atualização do mesmo estoque.
- **Regressão:** execução focada de `OrderServiceTest`, `OrderControllerIntegrationTest`, `OrderConcurrencyIntegrationTest`, testes de optimistic locking de produto, autorização/JWT e `PostgresContainerIntegrationTest`; depois suíte completa conforme tarefa final aprovada.
- Nenhum resultado de teste é alegado neste planejamento; nenhum teste foi executado.

## 6. Riscos e mitigação

- **Lock do pedido:** testar `PESSIMISTIC_WRITE` contra PostgreSQL real e confirmar que a consulta não causa lock inesperado nas relações de itens.
- **Falha durante devolução de vários produtos:** manter uma transação e testar rollback após pelo menos uma alteração ter sido preparada/flushada.
- **Concorrência com outras operações de estoque:** preservar `Product.version`; o conflito existente responde 409 e reverte cancelamento inteiro.
- **Overflow:** somar como inteiro com verificação antes de atribuir; overflow deve falhar e reverter, nunca persistir wraparound.
- **Pedido sem itens preexistente:** criação da API exige itens, mas schema não garante isso. Regra deste planejamento: cancelar como `CANCELLED` sem ajuste de estoque, pois não há item/quantidade para devolver. Testar essa regra e não inventar devolução.
- **JSON/formato de erros:** formato para ID malformado depende do resolver MVC já usado; testar antes de prometer corpo `ApiError` nesse caso.
- **Mensagem do novo 409:** manter neutra, estável e específica à exceção nova; não expor estado interno adicional.

## 7. Dependências e ordem

`T-01 → T-02 → T-03 → T-04 → T-05`. T-01 estabelece e testa regra transacional no serviço; endpoint/mapeamento dependem dela; testes integrados de JWT e concorrência dependem do contrato exposto; a validação final depende das anteriores. Cada tarefa requer aprovação explícita individual antes da implementação, mesmo após aprovação do plano.

## 8. Estado

Planejamento proposto; aguardando aprovação da iniciativa e autorização explícita da primeira tarefa. Não houve alteração de código, configuração, banco ou teste.
