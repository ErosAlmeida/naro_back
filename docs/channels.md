# Channels e autoria pública

O usuário autenticado é o dono dos canais. Publicações, comentários, respostas e
likes pertencem a um `Channel`. Um usuário pode ter vários canais; cada publicação
possui exatamente um canal como autor.

## Endpoints de Channel

| Método | Endpoint | Comportamento |
| --- | --- | --- |
| POST | `/api/v1/channels` | Cria um canal para o usuário autenticado. |
| GET | `/api/v1/channels/me` | Lista os canais do usuário autenticado em `_embedded.channels`; sem `_embedded` se ele ainda não tem nenhum. |
| GET | `/api/v1/channels/{id}` | Busca por UUID; retorna 404 se não existir. |
| GET | `/api/v1/channels/slug/{slug}` | Busca por slug; retorna 404 se não existir. |
| PUT | `/api/v1/channels/{id}` | Atualiza um canal do usuário autenticado. |
| GET | `/api/v1/channels/discover` | Descobre canais de outros usuários, com busca por nome ou slug e paginação. |
| POST | `/api/v1/channels/{id}/following/{targetId}` | O canal `id` passa a seguir `targetId` (201). |
| DELETE | `/api/v1/channels/{id}/following/{targetId}` | Deixa de seguir (204). |
| GET | `/api/v1/channels/{id}/following` | Lista canais seguidos, com paginação. |
| GET | `/api/v1/channels/{id}/followers` | Lista seguidores, com paginação. |
| GET | `/api/v1/channels/{id}/following/count` | Conta quantos canais `id` segue. |
| GET | `/api/v1/channels/{id}/followers/count` | Conta seguidores de `id`. |
| GET | `/api/v1/channels/{id}/following/{targetId}/status` | Informa se `id` segue `targetId` (`following`) e se o marcou como favorito (`favorite`). |
| PATCH | `/api/v1/channels/{id}/following/{targetId}/favorite` | Define `favorite` com JSON `{ "favorite": true/false }`. |

Criação e atualização usam `multipart/form-data`, com `name` e `slug`
obrigatórios, `description` opcional e arquivos opcionais `profile_image` e
`banner_image`. Na atualização, imagens ausentes mantêm as atuais; a descrição
ausente é removida. Slugs duplicados retornam 409 e operações em canais de outro
usuário retornam 403. O dono não pode ser alterado pela requisição.

O front descobre se a pessoa já tem canal por `GET /api/v1/channels/me`: coleção
vazia (resposta sem `_embedded`, veja [api.md](api.md)) significa que ela ainda
precisa criar o primeiro. Não há checagem prévia de
disponibilidade de slug; a criação e a atualização respondem 409 quando ele já
existe.

## Seleção do canal

Envie `X-Channel-Id: <UUID do canal>` para publicar, comentar, responder,
curtir, descurtir ou excluir um comentário. O backend valida que esse canal
pertence ao usuário do token. Não basta conhecer o UUID de outro canal.

Nas leituras de publicações, comentários e resumos de likes, o header é opcional.
Sem ele, `likedByCurrentChannel` é `false`; as contagens continuam completas.
Com ele, o campo informa exclusivamente o like do canal selecionado. A
autenticação por token continua obrigatória, conforme a configuração existente.

Na descoberta, `X-Channel-Id` é obrigatório: ele define qual canal está
explorando e para qual canal o campo `following` será calculado. O backend
confere que o canal pertence ao usuário autenticado e exclui da listagem todos
os canais do mesmo dono. `GET /api/v1/channels/discover` aceita `search`,
`page` e `size`; a ordem padrão é pelos canais mais recentes. Cada item traz
`followersCount` e `following`. A troca de canal ativo no frontend não troca a
conta autenticada.

Follow é sempre `Channel -> Channel`. Os endpoints de alteração e status usam
`{id}` como canal seguidor; o backend valida a posse antes de alterar a relação.
Seguir a si mesmo retorna 400, repetir um follow retorna 409, e canal ou
relação inexistente retorna 404. As contagens e listagens de seguidores e
seguidos são separadas para cada canal.

A tabela `channel_follows` mantém `favorite`, `interaction_count`,
`last_interaction_at` e `relevance_score` para uso futuro. No follow, esses
campos começam em `false`, `0`, `null` e `null`. Nenhum score ou recomendação
é calculado nesta etapa.

`GET /api/v1/publications?authorId=<UUID>` agora filtra pelo UUID do **canal**.
O payload de criação de publicações e comentários mantém os campos existentes;
a autoria vem do header validado.

Mudanças nas respostas:

- Publicação: `author` traz `id`, `name` e `slug` do canal, como em `CommentResponse`,
  e substitui `authorId`/`authorUsername`. O `slug` é o endereço na URL; o nome
  exibido é o `name`.
- Comentário: `author` contém `id`, `name` e `slug` do canal.
- Autoria (publicação e comentário): `author.profileImageUrl` traz a foto do
  canal já como URL de leitura pré-assinada, ou `null` quando ele não tem uma.
- Like: `channelId` e `slug` substituem `userId` e `username`.
- Resumos e publicações/comentários: `likedByCurrentChannel` substitui `likedByCurrentUser`.
- Channel: `ownerId` identifica o dono sem serializar a entidade `User`.
- Channel: `profileImageUrl` e `bannerImageUrl` substituem as chaves
  `profileImageKey`/`bannerImageKey` e vêm pré-assinadas, como o `imageUrl` da
  publicação. São `null` quando o canal não tem aquela imagem.

Cada canal pode curtir uma publicação ou comentário uma única vez. Repetições
normais retornam o resumo existente; disputas concorrentes são barradas pelo
índice único e retornam 409. Canais diferentes do mesmo dono podem curtir o mesmo
alvo. Descurtir e excluir comentários atuam apenas pelo canal selecionado.

## Migração

`V7` cria `channels`. `V8` cria um canal legado por usuário que já tenha alguma
publicação, comentário ou like, com nome baseado no username e slug
`legacy-<UUID do usuário>`. O canal legado reutiliza esse UUID para preservar as
referências existentes, mas continua sendo uma entidade independente de `User`.

`V9` cria `channel_follows`, com unicidade do par seguidor/seguido,
verificação contra auto-follow e índices para consultar seguidores e seguidos.

As colunas antigas `author_id` e `user_id` tornam-se `channel_id`, com foreign
keys para `channels`. Os índices únicos de likes passam a garantir unicidade por
canal e alvo. As relações entre comentários, respostas e publicações, seus dados
e timestamps são preservados. As migrations anteriores não foram alteradas.

As migrations são executadas pelo Flyway na inicialização da aplicação. Para
validar a suíte, incluindo migração de um banco na versão 6 e testes com
PostgreSQL/MinIO, execute `./mvnw test` com Docker disponível.
