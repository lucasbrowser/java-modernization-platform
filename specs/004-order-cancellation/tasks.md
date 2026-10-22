# Tarefas: Cancelamento de pedidos com devolução de estoque

**Estado da iniciativa:** em andamento; T-01 concluída, tarefas seguintes aguardam autorização específica.  
**Regra SDD:** a aprovação do plano não conclui aprovação automática das tarefas; cada tarefa de implementação aguarda autorização específica.

## T-01 — Implementar a transição transacional no serviço

- **Estado:** concluída em 2026-10-22, com validação unitária e compilação limpa.
- **Dependências:** aprovação da iniciativa e autorização explícita desta tarefa.
- **Escopo:** consulta do pedido sob lock de escrita; elegibilidade por estado; autorização pela `OrderAuthorizationService`; devolução de quantidades por produto; alteração para `CANCELLED`; repetição sem efeitos duplicados; exceção de domínio específica para estado não cancelável.
- **Arquivos prováveis:** `OrderRepository.java`, `OrderService.java`, nova exceção do pacote `order`, `OrderServiceTest.java`.
- **Testes previstos:** pedido próprio/ADMIN; terceiro como não encontrado; inexistente; usuário sem vínculo; estado CREATED; repetição CANCELLED; estado CONFIRMED; pedido legado sem itens; vários itens do mesmo produto e vários produtos; estoque e status não alterados quando a regra rejeita a operação.
- **Critérios objetivos de conclusão:** serviço implementa as regras de estado/autorização; em transação, sucesso devolve quantidades e persiste CANCELLED; pedido sem itens é cancelado sem ajuste de estoque; repetição é no-op; estados não canceláveis falham com exceção específica sem mutação; testes unitários pertinentes passam. Ainda não afirmar concorrência ou rollback real de PostgreSQL nesta tarefa.

### Resultado executado da T-01 — 2026-10-22

- `OrderRepository.findByIdForUpdate(UUID)` usa uma consulta JPQL explícita anotada com `@Lock(LockModeType.PESSIMISTIC_WRITE)`. `OrderService.cancel` consulta o pedido por esse método antes de verificar propriedade/estado; o teste de serviço verifica o uso do método bloqueante. A anotação configura o lock JPA na consulta ao banco. Nesta tarefa não foi executado teste concorrente com PostgreSQL, portanto a garantia observada de serialização concorrente permanece pendente para T-04.
- O serviço é `@Transactional`, usa `Product.version` existente, altera status e estoque na mesma unidade de trabalho e chama `flush()` antes de retornar o resultado de cancelamento. `Math.addExact` impede overflow silencioso no estoque e propaga falha em vez de persistir valor incorreto.
- Testes adicionados verificam USER próprio e ADMIN, terceiro mascarado como pedido inexistente, escopo negado antes de carregar o pedido, pedido inexistente, estado `CONFIRMED` sem mutação, repetição de `CANCELLED` sem novo ajuste, pedido vazio e restauração de múltiplos itens/produtos.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean "-Dtest=OrderServiceTest" test` — BUILD SUCCESS; compilou 48 fontes de produção e 22 fontes de teste; `OrderServiceTest`: 22 testes, 0 falhas, 0 erros, 0 ignorados.
- Uma tentativa do mesmo comando no sandbox falhou durante a leitura de `spring-security-web-6.3.4.jar` com `Acesso negado`; repetição com permissão elevada completou e passou. Nenhum código foi alterado para contornar o ambiente.
- Atomicidade/rollback após falha de persistência e serialização concorrente real continuam sem comprovação de integração PostgreSQL e permanecem para T-04. T-01 não alega esses resultados.

## T-02 — Expor o endpoint e o contrato de conflito

- **Estado:** proposta; depende de T-01 concluída e aprovada.
- **Escopo:** adicionar `POST /orders/{id}/cancel`, retorno 200 com `OrderResponse` existente; mapear apenas a exceção nova para 409 `ApiError` com mensagem fixa; manter handlers atuais.
- **Arquivos prováveis:** `OrderController.java`, `GlobalExceptionHandler.java`, teste MVC de pedidos e, se necessário, teste unitário do handler.
- **Testes previstos:** sucesso e resposta sem campos novos; inexistente/terceiro 404 com mensagem igual; repetição 200; estado não cancelável 409 com `ApiError`; ID malformado para caracterizar status e corpo MVC; regressão dos handlers 400/401/403/404/409/500 já existentes.
- **Critérios objetivos de conclusão:** rota sem body e status/DTO conforme especificação; nova exceção dá 409 sem alterar outras exceções; resposta por terceiro é indistinguível de inexistente; testes de MVC passam.

## T-03 — Validar autorização ponta a ponta com JWT

- **Estado:** proposta; depende de T-02 concluída e aprovada.
- **Escopo:** integrar identidade persistida e segurança real à rota de cancelamento, sem enfraquecer `SecurityConfig`.
- **Arquivos prováveis:** teste dedicado de integração de pedidos, fixtures/utilidades de JWT existentes; produção somente se teste demonstrar defeito concreto de autorização.
- **Testes previstos:** não autenticado 401; USER vinculado cancela pedido próprio; USER sem vínculo 403; USER de terceiro 404 sem vazamento; ADMIN cancela pedido alheio; pedidos e estoque permanecem inalterados nos casos negados. Usar PostgreSQL/Testcontainers e token JWT real emitido pelo serviço quando apropriado.
- **Critérios objetivos de conclusão:** todos os status/escopos são comprovados pela cadeia real de filtro e identidade; nenhuma regra das iniciativas 002/003 é enfraquecida ou alterada indevidamente.

## T-04 — Provar atomicidade, concorrência e integridade no PostgreSQL

- **Estado:** proposta; depende de T-03 concluída e aprovada.
- **Escopo:** testes de integração para rollback, estado/estoque persistidos, lock do pedido, concorrência de cancelamentos e concorrência com outras atualizações de estoque.
- **Arquivos prováveis:** novo `OrderCancellationIntegrationTest` ou extensão localizada de `OrderConcurrencyIntegrationTest`; fixtures Testcontainers existentes.
- **Testes previstos:** sucesso reflete CANCELLED e estoque; injetar falha determinística de persistência sem infraestrutura de produção e comprovar rollback integral; duas chamadas concorrentes restauram exatamente uma vez; versão Product preserva consistência com operação concorrente; pedido CONFIRMED/repetição não causam estoque duplicado.
- **Critérios objetivos de conclusão:** testes executados contra PostgreSQL/Testcontainers mostram estado final correto, nenhuma atualização parcial e restauração única sob concorrência; método de falha de teste não adiciona endpoint nem chave de produção.

## T-05 — Regressão final e fechamento documental

- **Estado:** proposta; depende de T-01 a T-04 concluídas e aprovadas.
- **Escopo:** mapear critérios CA-01 a CA-14 para testes, revisar diff funcional, executar testes direcionados e suíte completa e registrar resultados reais nesta iniciativa.
- **Arquivos prováveis:** `spec.md`, `plan.md`, `tasks.md`; correções de produção/teste somente se autorizadas e se defeito for demonstrado.
- **Testes previstos:** testes unitários e de integração de pedido, concorrência de estoque, autorização, contrato de erro e suíte Maven completa definida na aprovação da tarefa.
- **Critérios objetivos de conclusão:** critérios aceitos estão rastreados a testes; todos os comandos têm resultado real registrado; nenhuma regressão ou limitação é ocultada; iniciativa só recebe estado concluído após evidência.

## Primeira tarefa de implementação

A primeira tarefa proposta é **T-01 — Implementar a transição transacional no serviço**. Sua conclusão objetiva exige: leitura/lock do pedido; verificação de propriedade/estado; alteração atômica do pedido e do estoque; repetição segura; exceção de domínio específica para estados não canceláveis; testes unitários passando. T-01 não pretende comprovar ainda o comportamento de lock concorrente/rollback no PostgreSQL, que fica para T-04.

## Histórico de execução

Nenhuma tarefa foi aprovada ou executada. Não houve alteração de código, migration, configuração ou teste, e nenhum comando de teste foi executado nesta rodada de planejamento.
