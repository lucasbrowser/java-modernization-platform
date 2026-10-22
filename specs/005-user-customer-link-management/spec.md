# Especificação: Administração de vínculo User–Customer

**Iniciativa:** `005-user-customer-link-management`  
**Estado:** proposta documental, aguardando aprovação da iniciativa  
**Base:** decisões de negócio recebidas em 2026-10-22 e inspeção do código existente. Nenhuma implementação ou teste desta iniciativa foi executado.

## 1. Objetivo e problema de negócio

Permitir que um administrador autorizado associe, troque ou remova o Customer de um usuário `USER`, registrando a mudança em auditoria. Hoje o vínculo opcional está persistido, mas não existe uma operação da aplicação para administrá-lo. Usuários `USER` sem vínculo recebem `403` ao acessar operações de pedidos, e o registro público cria `USER` sem aceitar `customerId`.

## 2. Fatos existentes

- `User.customerId` é um UUID opcional. A migration V4 fornece FK para `customers(id)` e índice não único; vários usuários podem compartilhar um Customer.
- A migration V4 também cria `user_customer_link_audit`, com ator e alvo vinculados a `users`, snapshots dos Customers anterior/novo, ação `CREATE`/`CHANGE`/`REMOVE`, instante e justificativa. Um trigger bloqueia `UPDATE` e `DELETE` na tabela.
- `UserCustomerLinkAudit`, `UserCustomerLinkAction` e `UserCustomerLinkAuditRepository` já existem. Não foi encontrado controller ou serviço que altere o vínculo nem método de consulta do histórico.
- `AuthenticatedUserResolver.resolveCurrent()` localiza o usuário persistido pelo principal JWT e devolve `userId` e `customerId`; não devolve o papel persistido.
- `SecurityConfig` usa a role do JWT para autenticação/autorização e habilita method security. A iniciativa requer verificação adicional da role persistida do ator.
- `GlobalExceptionHandler` mapeia `ResourceNotFoundException` a 404, falhas de validação a 400, `IllegalArgumentException` a 400 e exceções inesperadas a 500 genérico. Não existe mapeamento específico para conflito de vínculo.
- `OrderAuthorizationService` permite a `USER` acesso a pedidos somente quando há Customer associado; `ADMIN` tem escopo amplo.

## 3. Decisões de negócio aprovadas para o planejamento

1. Somente ADMIN pode administrar vínculos; além da autorização de segurança existente, o serviço valida que o ator autenticado corresponde a usuário persistido com papel `ADMIN`.
2. Somente contas persistidas com papel `USER` podem ser alvo. Contas ADMIN não podem ter vínculo alterado por esta operação.
3. Solicitar a associação que o usuário já possui é operação sem efeito, sem novo evento de auditoria.
4. A justificativa é obrigatória, não pode ficar vazia após normalização e tem no máximo 500 caracteres.
5. As mudanças usam a auditoria existente. Esta iniciativa não cria endpoint para consultar ou exportar histórico.
6. A API terá operações REST explícitas de associação/troca e remoção.

Essas decisões não aprovam a iniciativa nem sua implementação. A operação administrativa de vínculo foi explicitamente deixada para especificação própria em `002-authorization-policy`.

## 4. Escopo

- Associar um usuário `USER` sem vínculo a um Customer existente.
- Trocar o Customer de um usuário `USER` vinculado.
- Remover o vínculo existente de um usuário `USER`.
- Validar o ator e o alvo usando registros persistidos.
- Obter o ator do principal autenticado; não aceitar `actorUserId` informado pelo cliente.
- Atualizar `users.customer_id` e inserir o evento correspondente na mesma transação.
- Serializar mudanças simultâneas ao vínculo de um mesmo usuário e manter a auditoria consistente com os valores anterior/novo.
- Preservar as regras existentes de autorização de pedidos e o contrato atual de login/registro.

## 5. Fora de escopo

- Associação automática no registro ou por igualdade de e-mail.
- Alterar papéis, credenciais, dados gerais de usuários ou dados gerais de Customers.
- CRUD geral de usuários ou Customers.
- Associar contas ADMIN.
- Consultar, exportar ou administrar retenção do histórico de auditoria.
- Alterar permissões de pedidos, cadastro/login ou outros contratos existentes.
- Nova tabela, migration ou dependência, salvo necessidade demonstrada e aprovada separadamente.

## 6. Proposta inicial de contrato REST

Os caminhos abaixo são relativos ao context path `/api`. Os endpoints são novos. A API existente retorna DTOs diretamente; `POST` de criação retorna `201 Created`, enquanto não há exemplo de atualização/remoção que determine o status de sucesso. Os contratos desta seção são recomendações para aprovação junto com a iniciativa.

### Associar ou trocar

`PUT /admin/users/{userId}/customer-link`

```json
{
  "customerId": "e59bdf14-902b-4688-9dd6-8078e91f744a",
  "justification": "Alocação do usuário ao cliente aprovada pelo suporte"
}
```

Recomendação de sucesso: `200 OK`, com representação mínima:

```json
{
  "userId": "8d366ec9-24c9-4d1b-8206-7b13346b8599",
  "customerId": "e59bdf14-902b-4688-9dd6-8078e91f744a",
  "changed": true
}
```

Para repetição da mesma associação, a resposta recomendada é `200 OK` com `changed: false`; nenhuma linha de auditoria é criada, conforme a decisão de negócio aprovada. `changed` representa a alteração efetiva desta chamada, não um valor da tabela de auditoria. O uso de `200` e deste DTO é recomendação, pois não há contrato existente de atualização.

### Remover

Recomendação: `POST /admin/users/{userId}/customer-link/removal`, com corpo:

```json
{
  "justification": "Desassociação solicitada e aprovada"
}
```

O uso de `POST` evita depender de corpo em `DELETE`, para o qual não há precedente nos contratos nem nos testes existentes; a justificativa segue no corpo e não na URL. Recomenda-se `200 OK` com a mesma representação da associação, `customerId: null` e `changed: true` para remoção efetiva:

```json
{
  "userId": "8d366ec9-24c9-4d1b-8206-7b13346b8599",
  "customerId": null,
  "changed": true
}
```

Se o vínculo já estiver ausente, recomenda-se tratar remoção como repetição idempotente: `200 OK`, `customerId: null`, `changed: false` e nenhum novo evento. Esta semântica não foi decidida explicitamente; depende de aprovação de negócio. Não colocar justificativa em query string, pois ela pode ser registrada em logs de URL.

### Respostas e casos sem decisão de contrato

- Sem autenticação: `401` pelo mecanismo de segurança existente.
- Autenticado sem autoridade ADMIN: `403` pelo mecanismo existente.
- Usuário ou Customer não encontrado: `404` via `ResourceNotFoundException`; texto exato para estes novos casos deve ser definido na T-01, mantendo o formato `ApiError` atual.
- Justificativa ausente/inválida ou corpo ilegível: `400` no contrato atual de validação/desserialização e `ApiError`.
- Falha inesperada de persistência/auditoria: `500` genérico existente, com rollback da transação.
- Tentativa de modificar alvo ADMIN: recomenda-se `400 Bad Request` com `ApiError`, por ser um tipo de alvo inválido para esta operação e por reutilizar o handler existente de `IllegalArgumentException`. Não há precedente de endpoint que valide tipo de alvo; esta é recomendação de contrato e depende de aprovação.
- Remoção de vínculo já ausente: recomenda-se o sucesso idempotente descrito acima, sem auditoria. A decisão de negócio não pode ser inferida dos contratos existentes e depende de aprovação.
- `200 OK` e o DTO de resultado são recomendações coerentes com retorno direto de DTO usado nos controllers e com a resposta observável da operação de cancelamento; nenhum endpoint existente de update/delete determina essa escolha.

## 7. Regras de validação propostas

- `userId`, `customerId` e ator são UUIDs persistidos; o ator vem do principal autenticado e não aparece em nenhum campo de entrada.
- O usuário alvo precisa existir e ter papel `USER` no banco.
- O Customer de destino precisa existir no banco. A FK continua sendo a última garantia de integridade referencial.
- A justificativa deve ser obrigatória e não vazia após `String.strip()`; validar no máximo 500 caracteres após `strip()` e persistir a forma resultante. Remover apenas espaços Unicode de borda reconhecidos por `String.strip()`; preservar espaços internos sem colapsá-los. DTOs existentes usam `@NotBlank`/`@Size`, mas não demonstram normalização persistida. O serviço deve aplicar `strip()` antes de persistir e validar o tamanho normalizado. Uma validação `@Size(max=500)` sobre o texto bruto não deve rejeitar um valor cujo texto stripped tenha até 500 caracteres; se usada, deve ser composta com validação após normalização. Essa regra operacional é recomendação explícita e depende de aprovação.
- Associar o Customer já associado não altera usuário nem auditoria.
- Para associação nova, gravar `CREATE`, anterior `NULL`, novo igual ao Customer solicitado.
- Para troca, gravar `CHANGE`, anterior igual ao vínculo persistido e novo igual ao solicitado.
- Para remoção efetiva, gravar `REMOVE`, anterior igual ao vínculo persistido e novo `NULL`.
- Para remoção repetida de vínculo ausente, aplicar a recomendação idempotente da seção 6, sem evento.

## 8. Auditoria, transação e concorrência

O evento registra ator persistido, alvo, snapshots anterior/novo, ação, instante UTC preenchido pela entidade/banco e justificativa. A requisição não pode escolher ator, timestamps, ação ou vínculo anterior. O histórico permanece append-only e não será exposto por endpoint nesta iniciativa.

Atualização do usuário e inserção do evento devem ser atômicas: se a gravação ou o flush da auditoria falhar, o vínculo anterior permanece persistido. Mudanças concorrentes ao mesmo usuário devem ser serializadas ou detectadas sem sobrescrever vínculo sem que o evento reflita o valor efetivamente substituído. A estratégia será definida no plano técnico; a entidade `User` atualmente não declara `@Version` e `UserRepository` não declara consulta com lock.

## 9. Critérios de aceitação

- **CA-01 — Autenticação:** requisição anônima às operações recebe `401` e não altera usuário nem auditoria.
- **CA-02 — Autorização por token:** usuário autenticado sem autoridade ADMIN recebe `403` e não altera dados.
- **CA-03 — Ator persistido:** uma role ADMIN no JWT, sem registro persistido ADMIN correspondente, não é suficiente para modificar vínculo; `actor_user_id` é o UUID do ADMIN persistido resolvido do principal.
- **CA-04 — Alvo elegível:** somente usuário persistido com role USER pode ser alvo. Alvo ADMIN é rejeitado com `400 ApiError` conforme recomendação a aprovar; usuário e auditoria permanecem inalterados.
- **CA-05 — IDs existentes:** usuário alvo e Customer de destino inexistentes não causam alteração de vínculo nem evento de auditoria e usam o tratamento 404 existente.
- **CA-06 — Associação:** usuário USER sem vínculo é associado ao Customer indicado e um evento `CREATE` correto é persistido.
- **CA-07 — Troca:** vínculo existente é substituído e um evento `CHANGE` contém os UUIDs anterior/novo corretos.
- **CA-08 — Remoção:** vínculo existente vira `NULL` e um evento `REMOVE` contém Customer anterior e `new_customer_id=NULL`; sucesso recomendado `200` com DTO de resultado.
- **CA-09 — Repetição de associação:** associar novamente o mesmo Customer não altera vínculo nem quantidade/conteúdo de eventos de auditoria.
- **CA-09a — Repetição de remoção:** se aprovada a recomendação idempotente, remover vínculo ausente retorna `200`, `changed=false` e não cria evento.
- **CA-10 — Justificativa:** valor ausente/vazio após `strip()` ou acima de 500 caracteres após `strip()` é rejeitado com `400`; valor aceito é persistido após `strip()`, preservando espaços internos.
- **CA-11 — Entrada não confiável:** o contrato não aceita `actorUserId`, papel, vínculo anterior, ação ou instante fornecidos pelo cliente.
- **CA-12 — Atomicidade:** falha de persistência do evento reverte também a alteração em `users.customer_id`.
- **CA-13 — Concorrência:** mudanças concorrentes ao mesmo usuário produzem uma sequência serializável de vínculo e eventos, sem evento com snapshot anterior incompatível com o valor substituído.
- **CA-14 — Auditoria imutável:** o fluxo apenas insere eventos; os mecanismos existentes de append-only continuam impedindo UPDATE/DELETE.
- **CA-15 — Compatibilidade de autorização:** após associação/troca/remoção, a política existente de pedidos usa o Customer persistido atualizado em requisições subsequentes; nenhuma associação ocorre no registro ou por e-mail.
- **CA-16 — Contrato HTTP:** entradas inválidas usam `ApiError` 400 e recursos inexistentes usam `ApiError` 404 conforme handler atual; respostas recomendadas de sucesso são `200` com DTO de resultado; alvo ADMIN recebe `400`; remoção sem vínculo é `200 changed=false` sem auditoria. Essas três recomendações exigem aprovação antes da implementação.

## 10. Recomendações contratuais aguardando aprovação

1. Associação/troca: `PUT /admin/users/{userId}/customer-link`, `200 OK` e DTO `{userId, customerId, changed}`; mesma associação retorna `changed=false` sem auditoria.
2. Remoção: `POST /admin/users/{userId}/customer-link/removal`, JSON com `justification`, `200 OK` e o mesmo DTO com `customerId=null`; recomenda-se repetição sem vínculo como `changed=false`, sem auditoria. A rota POST evita corpo em DELETE.
3. Alvo ADMIN: `400 Bad Request` com `ApiError`, usando a semântica existente de entrada/tipo inválido. Não há precedente para esse caso; é recomendação de negócio/API.
4. Justificativa: aplicar `String.strip()`, rejeitar ausente/vazia ou com mais de 500 caracteres após strip e persistir o valor stripped; preservar espaços internos. DTOs usam `@NotBlank`/`@Size`, mas não demonstram normalização persistida. Uma validação de tamanho no DTO não pode ser aplicada ao valor bruto de forma que rejeite texto acima de 500 antes do strip, se o texto normalizado couber.

As recomendações resolvem a proposta documental para T-01, mas dependem da aprovação explícita da iniciativa/contrato antes de implementação. Nenhuma implementação está autorizada por este documento.
