# Amizades privadas

Amizades relacionam `User` com `User`. Elas não criam follows, canais,
conversas ou permissões de presença. A identidade vem do JWT do Keycloak
pelo `@CurrentUser`/`CurrentUserService`.

## Endpoints

Todas as rotas exigem `Authorization: Bearer <access_token>`.
Nenhuma recebe a identidade do usuário autenticado pelo body ou query.

| Método | Rota | Resultado |
| --- | --- | --- |
| GET | `/api/v1/friends/discover` | Pessoas reais adicionáveis, por amigos em comum |
| GET | `/api/v1/friends/discover?search=leo` | Busca pessoas reais, incluindo relacionamentos existentes |
| GET | `/api/v1/friends/relationships/{userId}` | Estado atual e UUID da solicitação pendente |
| POST | `/api/v1/friends/requests/{userId}` | Cria solicitação, `201` |
| GET | `/api/v1/friends/requests/received` | Solicitações recebidas pendentes |
| GET | `/api/v1/friends/requests/sent` | Solicitações enviadas pendentes |
| POST | `/api/v1/friends/requests/{requestId}/accept` | Aceita e cria amizade, `200` |
| POST | `/api/v1/friends/requests/{requestId}/reject` | Rejeita, `200` |
| DELETE | `/api/v1/friends/requests/{requestId}` | Cancela, `204` |
| GET | `/api/v1/friends` | Amigos com `friendsSince` |
| GET | `/api/v1/friends?search=maria` | Busca somente amigos aceitos por nome ou username |
| DELETE | `/api/v1/friends/{userId}` | Remove somente a amizade, `204` |

Listas seguem o HAL paginado existente: `page=0&size=20`, com tamanho entre
1 e 100. Os itens ficam em `_embedded.people`, `_embedded.friendRequests`
ou `_embedded.friends`; coleções vazias podem omitir `_embedded`.
Solicitações e amizades são ordenadas da mais recente para a mais antiga.
Na lista de amigos, `search` aceita até 50 caracteres, sem diferenciar maiúsculas
e minúsculas. Filtra antes da paginação e trata `%`, `_` e `\` literalmente.
Essa busca alimenta o seletor de amigos nas conversas; pendentes e contas
artificiais não aparecem nessa lista.

## Pessoas na Naro

Sem busca (ou com busca vazia), a descoberta exclui o próprio usuário,
amigos e solicitações pendentes nas duas direções **antes da paginação**.
Ordena por quantidade de amigos reais em comum, depois username em
minúsculas e UUID, para desempate estável. Contas novas continuam aparecendo
mesmo sem amigos em comum. Follows e canais não influenciam esse ranking.

Com `search` não vazio, busca username ou nome com até 50 caracteres e
retorna também amigos/solicitações, com os estados `NONE`, `FRIEND`,
`REQUEST_SENT` e `REQUEST_RECEIVED`. `%`, `_` e `\` são tratados literalmente.
Ambos os modos excluem contas artificiais `seed:*` e `friend-seed:*`, e não
contam essas contas como amigos em comum. A API também recusa solicitações
enviadas diretamente a um UUID artificial.

```json
{
  "_embedded": {
    "people": [
      {
        "id": "00000000-0000-4000-8000-000000000001",
        "username": "maria",
        "firstName": "Maria",
        "lastName": "Silva",
        "relationshipStatus": "NONE",
        "mutualFriendsCount": 2
      }
    ]
  },
  "page": { "size": 20, "totalElements": 1, "totalPages": 1, "number": 0 }
}
```

`GET /relationships/{userId}` retorna `{ "status": "REQUEST_RECEIVED",
"requestId": "<UUID>" }`. Para `NONE`/`FRIEND`, não há solicitação pendente.
Use essa rota para decidir a ação em um perfil ou resolver uma solicitação
que está fora da página atual; não é necessário baixar todas as páginas.
Ela exige uma pessoa real existente e retorna `400` para o próprio UUID.

Solicitações retornam `id`, `sender` e `receiver` (resumos com `id`,
`username`, `firstName`, `lastName`, `avatarUrl`), `status`, `createdAt` e
`respondedAt`. Aceitar/rejeitar/cancelar usa o UUID da solicitação;
adicionar/remover usa o UUID da pessoa. Keycloak ID e email não são expostos.

## Regras e persistência

- Solicitações começam `PENDING`. Somente o receiver pode aceitar/rejeitar,
  e somente o sender pode cancelar. Depois da resposta, uma nova ação
  retorna `409`.
- Enviar para si mesmo retorna `400`; enviar para um amigo ou duplicar uma
  solicitação pendente em qualquer direção retorna `409`.
- Identificadores inexistentes retornam `404`; ações de outra pessoa
  retornam `403`. Erros usam o `ProblemDetail` existente.
- Aceitação atualiza a solicitação e cria uma amizade numa única transação.
  Remoção apaga apenas a amizade; usuários e histórico permanecem. Uma
  nova solicitação é permitida depois de rejeição/cancelamento/remoção.
- A migration V10 garante pares únicos nas duas direções, FKs, status,
  ausência de autorrelacionamento e coerência de `respondedAt`.
- Locks da solicitação serializam respostas concorrentes. Envio, aceitação
  e remoção travam usuários em ordem fixa para impedir duplicação e envio
  simultâneo à criação da amizade. Conflitos fazem rollback e retornam `409`.
- A descoberta usa projeção SQL paginada que resolve status e amigos em
  comum, sem uma consulta por pessoa. As outras listas usam `EntityGraph`.

## Contas reais, sem mocks de amizade

O seed privado de amigos foi removido. A migration V11 remove somente
usuários cujo `keycloak_id` começa com `friend-seed:` e seus registros
relacionados. Ela preserva usuários reais, inclusive usernames `mock.*`,
e o seed público de canais (`seed:*`). Reiniciar o backend não recria mocks
ou restaura relacionamentos removidos.

Crie duas contas pela interface e entre com cada uma ao menos uma vez,
para provisionar seus usuários na API. Use dois perfis de navegador ou uma
janela anônima. A segunda conta aparece em Pessoas na Naro; envie pela
primeira e aceite em Solicitações recebidas pela segunda. As duas listas de
amigos passam a mostrar a amizade. Também é possível rejeitar, cancelar
e remover pela interface.

## Testar pela API

Os UUIDs abaixo são os retornados pela descoberta e pela criação da
solicitação, não IDs fixos de usuários fictícios.

```bash
export NARO_TOKEN='<access_token_da_primeira_conta>'
export NARO_API='http://localhost:8080/api/v1/friends'
export NARO_USER_ID='<uuid_da_segunda_conta>'

curl -H "Authorization: Bearer $NARO_TOKEN" "$NARO_API/discover"
curl -X POST -H "Authorization: Bearer $NARO_TOKEN" "$NARO_API/requests/$NARO_USER_ID"
curl -H "Authorization: Bearer $NARO_TOKEN" "$NARO_API/relationships/$NARO_USER_ID"

# Com o token da segunda conta e o UUID retornado ao enviar:
export NARO_TOKEN='<access_token_da_segunda_conta>'
export NARO_REQUEST_ID='<uuid_da_solicitacao>'
curl -H "Authorization: Bearer $NARO_TOKEN" "$NARO_API/requests/received"
curl -X POST -H "Authorization: Bearer $NARO_TOKEN" "$NARO_API/requests/$NARO_REQUEST_ID/accept"
curl -H "Authorization: Bearer $NARO_TOKEN" "$NARO_API"
```

## Testes

- `FriendServiceImplTest` e `FriendControllerTest`: permissões, transições,
  relacionamento individual, DTOs, HAL, JWT e validações.
- `FriendRepositoryTest`: filtros antes da paginação, ranking bidirecional,
  exclusão de artificiais, curingas literais e estados da busca.
- `FriendshipMigrationTest`: constraints e remoção limitada ao seed privado,
  incluindo filhos com FKs sem cascade, preservando contas e mensagens reais.
- `FriendServiceIntegrationTest`: concorrência e rollback com PostgreSQL.
- `FriendEndpointsIntegrationTest`: duas contas novas provisionadas por JWT
  descobrem, enviam, aceitam, removem, cancelam e rejeitam pela API completa,
  sem conta seed, serviço mockado ou canal.

Execute `./mvnw test` ou `./mvnw package` com Java 25 e Docker para os testes
PostgreSQL/MinIO. Sem Docker, os testes de integração são ignorados.
