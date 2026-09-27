# Swagger e HATEOAS

## Swagger

| O quê | Onde |
| --- | --- |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Contrato OpenAPI 3.1 (JSON) | http://localhost:8080/v3/api-docs |

Gerado pelo [springdoc-openapi](https://springdoc.org) 3.1, a linha compatível
com o Spring Boot 4.1. A documentação é pública; chamar os endpoints pelo
*Try it out* continua exigindo token.

Toda a configuração fica em `config/OpenApiConfig.java`. Nos controllers só
existem `@Tag` (o grupo) e `@Operation(summary = ...)` (a frase de cada
endpoint). O resto o springdoc lê do próprio código: rotas, parâmetros,
validações (`@NotBlank`, `@Size`), status (`@ResponseStatus`) e os schemas HAL.

### Login no Swagger

O botão **Authorize** oferece dois caminhos:

- **keycloak**: login pelo Keycloak (Authorization Code + PKCE). É o
  recomendado.
- **bearer-jwt**: colar um access token já emitido.

O caminho `keycloak` precisa de um client público no realm `social`. É uma
configuração única, feita no console do Keycloak (http://localhost:8180):

| Campo | Valor |
| --- | --- |
| Client ID | `naro-swagger` |
| Client authentication | Off (client público) |
| Authentication flow | só *Standard flow* |
| Valid redirect URIs | `http://localhost:8080/swagger-ui/oauth2-redirect.html` |
| Web origins | `http://localhost:8080` |
| PKCE Method (aba Advanced) | `S256` |

O client ID vem de `springdoc.swagger-ui.oauth.client-id` no
`application.yml`, e as URLs do Keycloak saem do `issuer-uri` que o resource
server já usa.

## HATEOAS

As respostas usam [HAL](https://datatracker.ietf.org/doc/html/draft-kelly-json-hal),
o formato padrão do Spring HATEOAS. O HAL vem mesmo com
`Accept: application/json`: o Spring usa HAL como o JSON padrão.

### Um recurso

Os campos continuam no mesmo lugar. Só entra `_links`, que diz para onde dá
para ir a partir dali:

```json
{
  "id": "5c1f…",
  "content": "Paraty",
  "likeCount": 3,
  "_links": {
    "self":     { "href": "http://localhost:8080/api/v1/publications/5c1f…" },
    "author":   { "href": "http://localhost:8080/api/v1/channels/9a2e…" },
    "comments": { "href": "http://localhost:8080/api/v1/publications/5c1f…/comments?page=0&size=20" },
    "likes":    { "href": "http://localhost:8080/api/v1/publications/5c1f…/likes?page=0&size=20" }
  }
}
```

### Listas e páginas

Os itens ficam em `_embedded.<nome>`. As páginas trazem os links de navegação e
o mesmo bloco `page` de antes:

```json
{
  "_embedded": {
    "publications": [ { "id": "5c1f…", "_links": { } } ]
  },
  "_links": {
    "first": { "href": "…/api/v1/publications?page=0&size=20" },
    "self":  { "href": "…/api/v1/publications?page=1&size=20" },
    "next":  { "href": "…/api/v1/publications?page=2&size=20" },
    "last":  { "href": "…/api/v1/publications?page=9&size=20" }
  },
  "page": { "size": 20, "totalElements": 190, "totalPages": 10, "number": 1 }
}
```

- Os links só aparecem quando fazem sentido: `first` e `last` só com mais de
  uma página, `prev` e `next` só quando existe a vizinha. `next` ausente
  significa última página.
- Os links preservam os filtros da requisição (por exemplo `authorId`).
- **Coleção vazia não tem `_embedded`**, como manda o HAL. Leia
  `_embedded?.publications ?? []`.

| Endpoint | Chave em `_embedded` |
| --- | --- |
| `GET /api/v1/channels/me` | `channels` |
| `GET /api/v1/channels/discover` | `discoverChannels` |
| `GET /api/v1/channels/{id}/following`, `GET /api/v1/channels/{id}/followers` | `followChannels` |
| `GET /api/v1/publications` | `publications` |
| `GET /api/v1/publications/{id}/comments`, `GET /api/v1/comments/{id}/comments` | `comments` |
| `GET /api/v1/publications/{id}/likes`, `GET /api/v1/comments/{id}/likes` | `likes` |
| `GET /api/v1/chats` | `conversations` |
| `GET /api/v1/chats/{id}/messages` | `messages` |
| `GET /api/users/search` | `users` |
| `GET /api/v1/friends` | `friends` |
| `GET /api/v1/friends/discover`, `GET /api/v1/friends/recommendations` | `people` |
| `GET /api/v1/friends/requests/received`, `GET /api/v1/friends/requests/sent` | `friendRequests` |
| `GET /api/v1/encryption/keys` | `encryptionKeys` |
| `GET /api/v1/encryption/identities` | `identities` |

### Links de cada recurso

| Recurso | Links |
| --- | --- |
| Usuário autenticado (`/api/users/me`) | `self`, `channels`, `conversations` |
| Perfil / resultado da busca | `self` |
| Canal | `self`, `publications` |
| Publicação | `self`, `author`, `comments`, `likes` |
| Comentário | `publication`, `author`, `replies`, `likes` (sem `self`: não há GET de um comentário) |
| Like | `channel` |
| Conversa | `messages` (sem `self`: não há GET de uma conversa) |
| Identidade E2EE (`/api/v1/encryption/identity`) | `self`, `keys` |

`/api/users/me` é a porta de entrada: dele se chega a tudo que é do usuário.

Continuam em JSON simples, sem HAL, as respostas de estado ou de ação:
resumo de likes, presença, ticket do WebSocket e status do E2EE
(`/api/v1/encryption/status`). Passkeys e chaves públicas vão sem links por
item, como as mensagens: não existe GET de um item isolado. Veja `docs/e2ee.md`. As mensagens do WebSocket
também não mudaram.

### Onde os links são montados

Cada recurso tem um `XxxModelAssembler` ao lado do controller (o padrão
`RepresentationModelAssembler` do Spring HATEOAS). O controller só chama
`assembler.toModel(...)`; um método privado com o nome da relação monta cada
link:

```java
@Override
public EntityModel<PublicationResponse> toModel(PublicationResponse publication) {
    return EntityModel.of(
            publication,
            self(publication),
            author(publication),
            comments(publication),
            likes(publication)
    );
}
```

Os links apontam para métodos de controller (`linkTo(methodOn(...))`), não para
strings, então renomear uma rota não quebra nenhum link. Os nomes em
`_embedded` vêm do `@Relation` de cada DTO.
