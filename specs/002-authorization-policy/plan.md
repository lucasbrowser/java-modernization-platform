# Plano técnico: Política de autorização de clientes e pedidos

## 1. Estado atual inspecionado

- O projeto é um artefato Maven único, sem submódulos ou `module-info.java`. Os pacotes `auth`, `customer`, `order`, `product` e `common` funcionam como limites arquiteturais por convenção.
- `order` já depende de `customer` e `product`; `common` depende de `auth` por meio de `DataInitializer`. `auth` não depende atualmente de `customer` ou `order`.
- `User` e `Customer` têm UUID como chave primária. `users.email` e `customers.email` são únicos. `Order` tem `ManyToOne` obrigatório com `Customer`, com FK e índice.
- Não existe vínculo User–Customer nas entidades nem nas migrations. A migration V1 cria dados de usuários e clientes sem associação; migrations posteriores acrescentam idempotência e versão de produto.
- Registro cria `USER` sem Customer. Login procura por e-mail e senha e emite JWT cujo subject é o e-mail; o filtro autentica com subject e role, sem exigir Customer.
- `OrderService` consulta a chave de idempotência e pode devolver o pedido antes de validar o cliente do corpo. A chave é globalmente única para valores não nulos.
- Os testes existentes incluem `AuthControllerIntegrationTest`, `RepositoryIntegrationTest`, `PostgresContainerIntegrationTest`, testes de controller e serviço de pedido, e usam `.with(user(...))` em parte dos cenários HTTP. Esse helper não valida o filtro JWT.

## 2. Decisões funcionais consolidadas

As decisões desta seção são critérios da especificação, não evidência de implementação:

- `USER` e `ADMIN` consultam clientes; somente `ADMIN` cria clientes pelo endpoint existente.
- Cada `User` tem zero ou um `Customer`; um `Customer` pode estar associado a vários usuários. Não há associação automática por e-mail. Usuários existentes permanecem sem vínculo até associação autorizada.
- Usuários sem vínculo podem autenticar-se, mas não podem consultar nem criar pedidos: responder `403 Forbidden`.
- `USER` consulta e cria pedidos somente para o Customer associado. `customerId` divergente no `POST /orders` é rejeitado com `403 Forbidden`, sem substituição silenciosa.
- Se `USER` consultar pedido de terceiro por `GET /orders/{id}`, responder `404 Not Found`, igualando o caso inexistente.
- Se `USER` solicitar em `GET /orders/customer/{customerId}` um ID diferente do associado, responder `404 Not Found`.
- `ADMIN` consulta e administra pedidos de todos os clientes nas operações existentes, respeitando domínio e integridade.
- Antes de devolver pedido encontrado por chave de idempotência, verificar autorização. Preservar os replays válidos, estoque e demais validações existentes.
- Não criar rota, tela ou fluxo administrativo para associação sem especificação e aprovação próprias.

## 3. Arquitetura e resolução de identidade

**Representação recomendada:** campo opcional `UUID customerId` em `User`, sem associação JPA direta, respaldado por FK para `customers(id)` e índice não único em `users(customer_id)`. A FK protege a integridade sem criar dependência de entidade `auth -> customer`; a nulabilidade preserva os registros atuais e a multiplicidade no índice permite vários usuários por Customer.

Uma relação JPA `User -> Customer` é tecnicamente compatível com o monólito e não criaria ciclo hoje, mas adiciona dependência entre pacotes e navegação de entidades sem necessidade demonstrada. Uma tabela associativa é alternativa válida se o vínculo ganhar atributos próprios, mas acrescenta join e superfície de persistência. O campo UUID é a escolha de menor impacto coerente com o código observado.

O principal JWT atual é e-mail. Para a operação de autorização, resolver o `User` pelo principal autenticado usando o repositório existente e usar seu UUID persistido como identidade interna. Não usar o e-mail do Customer para inferir vínculo nem tornar a associação requisito de login. O mecanismo de acesso ao `customerId` precisa ser encapsulado sem duplicar regras nos controllers; usar o caminho de serviço mais simples e manter dependência unidirecional. Só introduzir porta/adapter se a implementação demonstrar necessidade concreta.

Centralizar a política de propriedade num serviço de aplicação usado pelo fluxo de pedidos. Controllers não devem duplicar comparação de IDs. A política distingue `USER` e `ADMIN`, e a decisão de negar precede a leitura da resposta do replay de idempotência.

## 4. Migração e compatibilidade

Adicionar por migration Flyway uma coluna `users.customer_id UUID NULL`, FK para `customers(id)` sem `ON DELETE CASCADE` e índice não único. Não alterar migrations já aplicadas. Não preencher a coluna para registros existentes, inclusive quando os e-mails coincidirem. A migration não apaga nem recria usuários ou clientes.

O `NULL` representa usuário ainda não associado e não impede autenticação. Uma referência inválida é rejeitada pela FK. O índice não único ajuda resolução/consulta por Customer sem impor exclusividade do Customer. Exclusão de Customer referenciado será bloqueada pela FK; política de remoção e desassociação do vínculo será tratada pela operação autorizada especificada separadamente.

Validar a migration tanto em banco vazio quanto partindo de schema equivalente à versão corrente com usuários e clientes existentes. Confirmar preservação de IDs e linhas e `customer_id IS NULL` para todos os usuários legados.

## 5. Auditoria e administração do vínculo

Não foi encontrado mecanismo genérico de auditoria nem operação de administração do vínculo. Para uma futura operação autorizada, planejar tabela de auditoria dedicada, append-only, com: ator (`actor_user_id`), usuário alvo (`target_user_id`), Customer anterior e novo (UUIDs como snapshots, nullable), ação (`CREATE`, `CHANGE`, `REMOVE`), instante UTC gerado pelo servidor e justificativa. Usar FK para ator e alvo; IDs de Customer ficam como snapshots sem FK para preservar histórico se o Customer for removido no futuro. Não permitir alteração/exclusão retroativa por fluxo normal.

Gravar evento e alterar associação na mesma transação: falha de auditoria reverte o vínculo. Resolver o ator a partir do principal JWT para o ID persistido; nunca confiar num ator informado pelo cliente. A tabela pode ser criada na migration de persistência como estrutura sem operação exposta. Perfil autorizado, retenção, acesso ao histórico e procedimento de correção são decisões de operação a especificar antes de criar mecanismo de associação. Nenhuma rota, tela, processo ou role administrativa nova está aprovada por este plano.

## 6. Regras HTTP e idempotência

| Situação para `USER` | Resposta planejada |
|---|---|
| Sem Customer ao consultar ou criar pedido | `403 Forbidden` |
| `customerId` divergente no `POST /orders` | `403 Forbidden` |
| Pedido de terceiro em `GET /orders/{id}` | `404 Not Found`, igual a inexistente |
| Customer divergente em `GET /orders/customer/{customerId}` | `404 Not Found` |

Para idempotência, identificar o pedido existente, verificar a propriedade e só então devolvê-lo. Replays de pedidos de terceiros não podem revelar sua existência. A chave é atualmente global; implementar essa verificação sem trocar silenciosamente a semântica de chaves válidas e sem repetir efeitos de estoque/criação. Cobrir combinação de chave existente com cliente do pedido e cliente enviado no replay.

## 7. Sequência de implementação proposta

As tarefas abaixo exigem aprovação explícita individual antes de implementação. **T-02.1 — Persistir a associação opcional e a trilha de auditoria** foi aprovada, implementada e testada nesta execução. As próximas tarefas permanecem propostas; T-02.1 não expõe operação de vínculo.

1. **T-02.1 — Persistência da associação e auditoria:** concluída. Migration V4 adiciona coluna nullable, FK, índice não único e tabela append-only; `User` tem UUID escalar e existe entidade/repositório de auditoria. Testes cobrem migration com dados legados, FK, nulabilidade, cardinalidade, e-mails coincidentes, persistência dos eventos e bloqueio de UPDATE/DELETE. Não foi criado serviço ou endpoint de administração.
2. **T-02.2 — Resolução de identidade e compatibilidade de autenticação:** resolver usuário persistido por e-mail do principal onde necessário; assegurar que registro e login continuam aceitando vínculo nulo e que JWT mantém subject/role. Testar integração de auth e resolução, sem atribuir Customer.
3. **T-03 — Autorização de criação de clientes:** restringir POST existente a ADMIN e preservar GET para USER/ADMIN. Testes de API para anônimo, USER e ADMIN.
4. **T-04 — Autorização de pedidos:** centralizar propriedade; aplicar decisões HTTP desta especificação a leitura por ID, leitura por Customer, usuário sem vínculo e criação; autorização antes de replay; manter domínio e estoque. Testes de serviço e API, inclusive JWT real, USER/ADMIN e replays.
5. **T-05 — Revisão de cobertura integrada:** completar lacunas de migração, cardinalidade, e-mail coincidente, matriz de autorização e contratos HTTP; comparar resultados com todos os critérios de aceitação. Não é motivo para adiar testes pertencentes a T-02.1–T-04.

Uma operação para criar, alterar ou remover vínculos requer especificação e aprovação própria, incluindo autorizadores, justificativa, auditoria, correção, retenção e contrato. Não é pré-requisito para adicionar a estrutura persistida, mas é pré-requisito para associar usuários em produção por operação da aplicação.

## 8. Testes de aceitação planejados

- **Persistência/cardinalidade:** usuário com zero ou um UUID; dois usuários para o mesmo Customer; nenhum `UNIQUE(customer_id)`; FK rejeita Customer inexistente; coluna aceita `NULL`.
- **Migração:** partir de versão anterior com usuários e Customers; aplicar migration e preservar linhas/IDs; todos os usuários legados ficam sem associação. Executar no PostgreSQL via Testcontainers/Flyway e validar constraints reais do banco.
- **Sem backfill:** User e Customer com e-mail idêntico continuam desvinculados; registro novo também não associa automaticamente.
- **Login:** usuário com associação nula registra-se/autentica-se; token mantém subject e role atuais.
- **Auditoria:** persistência de ator, alvo, valores anterior/novo, ação, instante UTC e justificativa; rollback conjunto em falha. Exercitar apenas quando o serviço de associação estiver aprovado e implementado.
- **Autorização/API:** anônimo `401`; sem vínculo `403` ao consultar/criar; pedido próprio permitido; pedido de terceiro por ID `404`; Customer divergente na rota de lista `404`; Customer divergente no POST `403`; ADMIN amplo; replay sem divulgação e sem efeitos duplicados. Cobrir JWT/filtro real além de `.with(user(...))`.
- **Regressão de domínio:** manter validação de produto, estoque, integridade e comportamento dos replays autorizados.

Os casos de persistência, migration legada, associação nula, email coincidente, auditoria e imutabilidade foram executados na T-02.1. A suíte completa passou: 101 testes, zero falhas e zero erros. Os testes de T-02.2 em diante continuam planejados e não foram executados nesta tarefa.

## 9. Riscos residuais e limites

- O JWT ainda usa e-mail como subject. A resolução consulta `User` em operações protegidas; mudança de e-mail e tokens emitidos anteriormente devem seguir o comportamento existente até eventual especificação própria.
- O uso de FK com snapshots de Customer na auditoria preserva histórico sem referenciar entidades removíveis, mas requer política de acesso e retenção antes de operação real.
- A associação opcional deixa usuários legados sem acesso a pedidos até vínculo autorizado, conforme decisão funcional.
- A chave global de idempotência exige cuidado para não revelar pedido de outro cliente no replay.
- As consultas de cliente existentes devolvem dados do DTO atual, inclusive e-mail/documento; esta política não altera esses campos.

## 10. Estado documental

Os status HTTP foram definidos na especificação. T-02.1 está concluída; T-02.2 e as demais tarefas continuam propostas e aguardam aprovação explícita. A política de autorização de pedidos ainda não foi implementada nesta tarefa.
