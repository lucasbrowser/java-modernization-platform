# Tarefas: Contrato de erros HTTP da API

As tarefas abaixo são propostas para execução após aprovação explícita. A aprovação da iniciativa não autoriza automaticamente cada tarefa, conforme o fluxo de `specs/README.md`.

## T-01 — Caracterizar cenários HTTP e fechar textos genéricos

- **Dependências:** aprovação explícita da iniciativa e desta tarefa.
- **Escopo:** criar cobertura de integração HTTP determinística para JSON malformado, tipo incompatível e falha inesperada controlada; registrar comportamento atual observado e definir textos genéricos estáveis, sem expor detalhes internos.
- **Arquivos prováveis:** testes de integração sob `src/test/java/com/github/lucasoliveira/platform/`; possivelmente `GlobalExceptionHandlerTest`.
- **Critérios de conclusão:** os testes exercitam o pipeline HTTP real; confirmam os status esperados; o cenário inesperado verifica que mensagem sentinela não aparece; segurança não é capturada pelo caminho genérico; fica confirmado se `ApiError` pode ser reutilizado sem mudança.
- **Testes previstos:** integração HTTP dos três cenários; testes pertinentes de segurança como regressão. Registrar o resultado sem alterar produção nesta tarefa de caracterização, se a separação for mantida na implementação aprovada.

## T-02 — Mapear erros de leitura JSON para 400

- **Dependências:** T-01 concluída e aprovação específica de T-02.
- **Escopo:** tratar erros de JSON malformado e tipo incompatível com corpo `ApiError` e mensagem genérica; não incluir detalhes do parser.
- **Arquivos prováveis:** `GlobalExceptionHandler.java` e testes HTTP de T-01.
- **Critérios de conclusão:** ambos os cenários retornam HTTP 400; corpo contém os campos atuais de `ApiError`; nenhuma mensagem interna do parser é exposta; validação Bean Validation permanece distinta e inalterada.
- **Testes previstos:** testes de integração HTTP dos dois tipos de falha e regressões para Bean Validation.

## T-03 — Mapear exceções inesperadas para 500 sem vazamento

- **Dependências:** T-01 concluída; T-02 pode ser executada antes ou em paralelo somente se a aprovação específica autorizar e não houver conflito nos mesmos arquivos.
- **Escopo:** resposta genérica HTTP 500 para exceções inesperadas abrangidas pelo handler; não implementar tratamento amplo de falhas de persistência além do necessário para a regra genérica acordada.
- **Arquivos prováveis:** `GlobalExceptionHandler.java` e testes HTTP de T-01.
- **Critérios de conclusão:** resposta `ApiError` com mensagem fixa genérica; corpo não contém mensagem da exceção, stack trace, SQL, nome de classe ou dado sensível; mapeamentos específicos existentes continuam prevalecendo; segurança mantém seu comportamento.
- **Testes previstos:** integração HTTP com exceção determinística portando texto sentinela; regressão de exceções específicas e segurança.

## T-04 — Verificar regressões e critérios da iniciativa

- **Dependências:** T-02 e T-03 concluídas e aprovadas.
- **Escopo:** verificar a matriz completa de erros no escopo, comparar status, campos e formatos atuais, e executar os testes pertinentes acordados.
- **Arquivos prováveis:** testes de handler, testes HTTP e, apenas se necessário, documentação desta iniciativa para registrar evidências.
- **Critérios de conclusão:** CA-01 a CA-10 avaliados; erros existentes e `fields` de validação preservados; respostas de sucesso e regras de negócio sem mudança; resultados e limitações registrados; nenhuma tarefa marcada como concluída sem evidência.
- **Testes previstos:** testes unitários do `GlobalExceptionHandler`, testes de integração HTTP novos e regressões pertinentes de auth/customer/product/order. Rodar a suíte acordada na aprovação da tarefa.

## Ordem e dependências

`T-01 → T-02 → T-03 → T-04` é a sequência recomendada. T-02 e T-03 compartilham o handler; qualquer paralelismo requer aprovação explícita e coordenação de edição. Não há migration nem alteração de schema prevista.

## Estado inicial antes da execução da T-01

Todas as tarefas estão **propostas / aguardando aprovação**. Nenhum teste de implementação foi executado e nenhuma tarefa foi aprovada ou concluída nesta iniciativa.

## Resultado da T-01 — caracterização parcial com limitações registradas

**Execução:** 2026-10-21. Nenhum código de produção foi alterado.

### Testes adicionados

- `src/test/java/com/github/lucasoliveira/platform/ApiErrorContractIntegrationTest.java`
  - JSON sintaticamente malformado em `POST /auth/login`: MockMvc retornou 400 e resolveu `HttpMessageNotReadableException`; corpo vazio, sem `ApiError`.
  - JSON válido com objeto no campo `password` (esperado `String`): MockMvc retornou 400 e resolveu `HttpMessageNotReadableException`; corpo vazio, sem `ApiError`.
  - `IllegalStateException` controlada pelo mock do `AuthService`, contendo sentinela/SQL/nome de classe: escapou do MockMvc como `ServletException`. Como não houve resposta serializada, este teste não afirma que o HTTP 500 está protegido contra vazamento.

MockMvc foi escolhido como alternativa próxima porque os ensaios com servidor aleatório retornaram 401 nas duas rotas de auth e na rota protegida de sonda, inclusive com JWT gerado pelo `JwtService`. A causa não foi identificada. Não houve alteração de segurança para forçar a passagem. Assim, status/corpo externos desses cenários permanecem pendentes para T-02.

### Mapeamentos existentes revisados

- A execução direcionada incluiu `AuthControllerIntegrationTest` (6 testes) e `GlobalExceptionHandlerTest` (5 testes). Isso cobre via MVC os fluxos de credenciais inválidas, e-mail registrado e validação, e diretamente no handler os casos de recurso inexistente, argumento inválido e optimistic locking.
- Nenhuma expectativa nem implementação de produção foi modificada.

### Comandos e resultados reais

- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" "-Dtest=ApiErrorContractIntegrationTest,AuthControllerIntegrationTest,GlobalExceptionHandlerTest" test` — BUILD SUCCESS; 14 testes, 0 falhas, 0 erros, 0 ignorados.
- `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean test` — BUILD SUCCESS; 118 testes, 0 falhas, 0 erros, 0 ignorados. PostgreSQL/Testcontainers foi executado.
- Durante a caracterização por servidor aleatório, uma execução separada de `ApiErrorContractIntegrationTest` terminou com 3 falhas, todas por resposta 401 em vez dos status esperados. Esse ensaio foi substituído pela alternativa MockMvc e permanece registrado como limitação, não como teste aprovado.

### Textos propostos e pendências para T-02

- Texto genérico proposto para JSON não legível: `Malformed request body`.
- Mensagem genérica proposta para exceção inesperada: `An unexpected error occurred.`.
- Esses textos não foram observados como respostas atuais e ainda não foram validados por asserção de resposta HTTP; devem ser implementados e testados na T-02.
- T-02 deve testar respostas HTTP efetivas para os dois erros de leitura e para a exceção inesperada, confirmar `ApiError` nos 400/500, e provar que a resposta 500 não contém a sentinela, SQL, mensagem interna, stack trace, nome de classe ou dado sensível.
- A estratégia do teste por servidor deve resolver o 401 de teste sem enfraquecer a segurança de produção. Preservar também regressões de sucesso, erros específicos e validação por campo.

**Estado da T-01:** caracterização MVC adicionada e executada; comportamento HTTP completo do servidor não confirmado. A limitação foi documentada; a mensagem 500 permanece proposta e não testada como resposta. Não iniciar T-02 sem nova aprovação explícita.
