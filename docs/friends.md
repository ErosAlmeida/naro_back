# Amizades privadas

Amizades relacionam `User` com `User`. Elas não criam follows, canais,
conversas ou permissões de presença. A identidade vem do JWT do Keycloak
pelo `@CurrentUser`/`CurrentUserService`.

## Endpoints

Todas as rotas exigem `Authorization: Bearer <access_token>`.
Nenhuma recebe a identidade do usuário autenticado pelo body ou query.

| Método | Rota | Resultado |
| --- | --- | --- |
| GET | `/api/v1/friends/discover?search=leo` | Busca pessoas reais pelo username (`leo` ou `@leo`), com o relacionamento |
| GET | `/api/v1/friends/recommendations` | Pessoas que talvez você conheça: somente amigos de amigos |
| GET | `/api/v1/friends/discover` | Sem `search`: as mesmas recomendações, por compatibilidade |
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
1 e 100. Os itens ficam em `_embedded.people` (busca e recomendações),
`_embedded.friendRequests` ou `_embedded.friends`; coleções vazias podem
omitir `_embedded`.
Solicitações e amizades são ordenadas da mais recente para a mais antiga.
Na lista de amigos, `search` aceita até 50 caracteres, sem diferenciar maiúsculas
e minúsculas. Filtra antes da paginação e trata `%`, `_` e `\` literalmente.
Essa busca alimenta o seletor de amigos nas conversas; pendentes e contas
artificiais não aparecem nessa lista.

## Buscar pessoas pelo username

`GET /discover?search=` é a busca manual e global: encontra qualquer conta
real cujo username contenha o termo, com ou sem `@` no início (`@leo`,
`leo`), sem diferenciar maiúsculas e minúsculas. Nome e sobrenome não entram
na busca. O termo tem até 50 caracteres; `%`, `_` e `\` são tratados
literalmente. O próprio usuário nunca aparece. O frontend só busca a partir
de 2 caracteres, com debounce.

Cada pessoa vem com o relacionamento (`NONE`, `FRIEND`, `REQUEST_SENT`,
`REQUEST_RECEIVED`) e os amigos em comum. A ordem é: username exato, depois
quem começa com o termo, depois quem só o contém; em cada grupo, mais amigos
em comum primeiro, depois username em minúsculas e UUID.

## Pessoas que talvez você conheça

`GET /recommendations` recomenda **somente amigos de amigos**: usuário
autenticado → amigos → amigos desses amigos, agrupados por pessoa com
`COUNT(DISTINCT amigo em comum)`. Uma pessoa aparece quando:

1. há pelo menos um amigo em comum;
2. não é o próprio usuário;
3. ainda não é amiga do usuário;
4. não há solicitação pendente em nenhuma direção (enviada ou recebida;
   recebidas ficam em Pedidos). Solicitações recusadas ou canceladas não
   escondem ninguém.

Não há recomendação aleatória, global, por popularidade, cidade, cadastro
recente ou seguidores: quem não tem amigos recebe uma lista vazia. A ordem é
por amigos em comum, depois username em minúsculas e UUID. Não existe
bloqueio na Naro; quando existir, deve entrar nesses filtros.

`GET /discover` sem `search` (ou só com `@`) devolve as mesmas
recomendações, para clientes antigos continuarem funcionando sem listar a
plataforma inteira. Novos clientes usam `/recommendations`.

Busca e recomendações excluem contas artificiais `seed:*` e `friend-seed:*`
e não contam essas contas como amigos em comum. A API também recusa
solicitações enviadas diretamente a um UUID artificial.

```json
{
  "_embedded": {
    "people": [
      {
        "id": "00000000-0000-4000-8000-000000000001",
        "username": "maria",
        "firstName": "Maria",
        "lastName": "Silva",
        "avatarUrl": null,
        "relationshipStatus": "NONE",
        "mutualFriendsCount": 4
      }
    ]
  },
  "page": { "size": 20, "totalElements": 1, "totalPages": 1, "number": 0 }
}
```

Não há `displayName` na API: o nome exibido é `firstName` + `lastName`,
ou o username quando ambos estão vazios, como no resto da Naro.

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
- Busca e recomendações são uma query SQL paginada cada, com projeção (sem
  carregar entidades) e sem uma consulta por pessoa. As outras listas usam
  `EntityGraph`.
- Nenhuma migration foi necessária para busca e recomendações. Os índices da
  V10 atendem a todos os passos: `idx_friendships_user_a_created` e
  `idx_friendships_user_b_created` para os amigos (nos dois sentidos),
  `uk_friendships_pair` para pares e os índices parciais `PENDING` de
  `friend_requests`. As recomendações agregam os candidatos antes de ler
  `users`, que só é lido pela chave primária. Medido com 100 mil usuários e
  1 milhão de amizades: recomendações em ~2 ms; busca em ~17 ms (termo
  seletivo) a ~60 ms (termo que casa todos). A busca por "contém" lê
  `users` sequencialmente; se a base crescer muito, o próximo passo é um
  índice GIN `pg_trgm` em `LOWER(username)`, numa migration própria.

## Contas reais, sem mocks de amizade

O seed privado de amigos foi removido. A migration V11 remove somente
usuários cujo `keycloak_id` começa com `friend-seed:` e seus registros
relacionados. Ela preserva usuários reais, inclusive usernames `mock.*`,
e o seed público de canais (`seed:*`). Reiniciar o backend não recria mocks
ou restaura relacionamentos removidos.

Crie duas contas pela interface e entre com cada uma ao menos uma vez,
para provisionar seus usuários na API. Use dois perfis de navegador ou uma
janela anônima. Na primeira, busque a segunda pelo username em Adicionar,
envie a solicitação e aceite em Solicitações recebidas pela segunda.
Com uma terceira conta amiga da segunda, a primeira passa a vê-la em
"Pessoas que talvez você conheça". As duas listas de
amigos passam a mostrar a amizade. Também é possível rejeitar, cancelar
e remover pela interface.

## Testar pela API

Os UUIDs abaixo são os retornados pela descoberta e pela criação da
solicitação, não IDs fixos de usuários fictícios.

```bash
export NARO_TOKEN='<access_token_da_primeira_conta>'
export NARO_API='http://localhost:8080/api/v1/friends'
export NARO_USER_ID='<uuid_da_segunda_conta>'

curl -H "Authorization: Bearer $NARO_TOKEN" "$NARO_API/discover?search=<username_da_segunda_conta>"
curl -H "Authorization: Bearer $NARO_TOKEN" "$NARO_API/recommendations"
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
- `FriendRepositoryTest`: recomendações só de amigos de amigos (Leonardo →
  João → Maria), sem amigos, sem pendentes nas duas direções, sem pessoas
  sem conexão e sem lista global; ranking por amigos em comum com desempate
  estável e paginação; busca global só por username com estados, ordem
  exato/prefixo e curingas literais; exclusão de artificiais.
- `FriendshipMigrationTest`: constraints e remoção limitada ao seed privado,
  incluindo filhos com FKs sem cascade, preservando contas e mensagens reais.
- `FriendServiceIntegrationTest`: concorrência e rollback com PostgreSQL.
- `FriendEndpointsIntegrationTest`: duas contas novas provisionadas por JWT
  se encontram pela busca (com e sem `@`), enviam, aceitam, removem, cancelam
  e rejeitam pela API completa, sem conta seed, serviço mockado ou canal; e
  quatro contas mostram recomendações só de amigos de amigos, que somem
  depois de uma solicitação.

Execute `./mvnw test` ou `./mvnw package` com Java 25 e Docker para os testes
PostgreSQL/MinIO. Sem Docker, os testes de integração são ignorados.
