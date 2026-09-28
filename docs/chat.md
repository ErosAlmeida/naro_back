# Conversas entre amigos

Uma conversa direta só pode ser criada ou reaproveitada quando existe uma amizade
aceita entre os dois usuários. Solicitações pendentes, follows, atividade em canais
e contas de seed não autorizam conversar. A API responde `403` para essas tentativas.

O backend verifica a amizade atual em cada envio de mensagem e evento de digitação,
inclusive em uma conversa antiga ou em um WebSocket conectado antes de remover a
amizade. A identidade do remetente continua vindo da sessão autenticada, nunca do
payload enviado pelo cliente.

## Interface

A tela `/chat` oferece as abas **Conversas** e **Amigos**. A aba Amigos carrega
automaticamente as amizades aceitas, busca por nome/username no servidor e permite
carregar mais páginas. Escolher uma pessoa abre ou reutiliza a conversa direta.
`/chat?aba=amigos` abre o seletor; a tela de amigos e o perfil também oferecem
**Mensagem** somente quando a amizade está aceita.

No celular, a lista e a conversa ocupam telas distintas com botão de voltar.
A altura acompanha o teclado virtual e a navegação inferior sai da tela durante
a conversa. A partir de tablet, as colunas ficam lado a lado. Telas largas mantêm
mensagens com largura limitada para leitura, e links ou palavras longas quebram
sem criar rolagem horizontal.

A interface atualiza a permissão ao recuperar foco, periodicamente e em erros
do WebSocket. Uma amizade encerrada mostra o histórico para leitura e bloqueia
o campo, tentativas de reenvio e eventos de digitação.

## Histórico e permissão de envio

`GET /api/v1/chats` inclui `canMessage` em cada conversa. Ele é `true`
quando o usuário pode enviar mensagens naquele momento. A consulta de amizades é
feita em lote para a lista inteira. A permissão informada na lista serve à interface;
o servidor sempre a valida novamente antes de enviar.

Quando a amizade acaba, `canMessage` passa a ser `false`. Os participantes continuam
podendo consultar o histórico em `GET /api/v1/chats/{id}/messages`.
Outros usuários continuam sem acesso ao histórico. Se a amizade for aceita novamente,
a conversa direta existente pode ser reutilizada e o histórico é preservado.

Não há criação pública de grupos. Caso um grupo já exista, o remetente precisa ser
amigo de todos os demais participantes. Conversas diretas com quantidade incorreta
de participantes, grupos sem destinatários ou conversas com contas de seed falham
de forma restritiva.

## Concorrência

Criação, envio e digitação bloqueiam as linhas dos participantes em ordem crescente
da representação UUID, a mesma ordem usada para aceitar e remover amizades. Assim,
envios concorrentes com uma remoção têm uma ordem definida: um envio que já recebeu
autorização termina antes da remoção; após a remoção, novos envios são recusados.
O mesmo bloqueio impede criar duas conversas diretas entre o mesmo par em chamadas
concorrentes.

## Erros no WebSocket

## Criptografia ponta a ponta

Com `naro.security.e2ee.required=true`, toda mensagem nova é cifrada no browser e o
servidor só aceita `{ conversationId, ciphertext, iv, encryptionVersion }`. Mensagens
antigas continuam em `content` (`encrypted = false`). O fluxo completo, as flags e
as limitações estão em `docs/e2ee.md`.

A fila privada `/user/queue/errors` retorna `FRIENDSHIP_REQUIRED` quando não existe
mais amizade autorizando o envio. O evento contém `conversationId` e, para mensagens,
o `clientMessageId` enviado pelo cliente. Isso permite que a interface interrompa o
envio e marque apenas a mensagem recusada. Eventos de digitação não têm
`clientMessageId`. Mensagens recusadas pelo E2EE voltam com `E2EE_REQUIRED`,
`E2EE_INVALID_PAYLOAD` ou `E2EE_UNAVAILABLE`, com a mesma correlação. Os outros
erros preservam `CHAT_ERROR` e `INVALID_PAYLOAD`.

## Notificações e não lidas

`GET /api/v1/chats` inclui `unreadCount` em cada conversa, e `GET /api/v1/chats/unread`
devolve o total (`conversations`, `messages`). Uma conversa é lida quando fica na tela:
o cliente envia `/app/chat/focus`, que também impede push dessa conversa enquanto ela
está aberta. Avisos sem conteúdo chegam em `/user/queue/notifications`, e o Naro fechado
recebe Web Push. O fluxo completo, a configuração VAPID e os testes por plataforma estão
em `docs/notifications.md`.

## Tiques de entrega e leitura

```
✓        salva no servidor
✓✓       chegou a um aparelho de quem recebe
✓✓ azul  quem recebe abriu a conversa
```

Cada participante tem dois marcadores por conversa em `conversation_reads`:
`last_delivered_at` e `last_read_at` (V15). Uma mensagem de outra pessoa está
entregue/lida quando foi criada até o marcador correspondente; ler implica
entregar, e os marcadores nunca voltam no tempo.

| Marcador | Quem move |
|---|---|
| Entregue | `/app/chat/delivered {conversationId, messageId}` a cada aviso de mensagem nova no Naro aberto; `/app/chat/delivered {}` a cada conexão (tudo o que já existe); `POST /api/v1/chats/{id}/delivered` pelo service worker quando o push chega com o Naro fechado |
| Lida | `/app/chat/focus` (a conversa na tela), como antes |

O marcador de entrega vai até a mensagem confirmada (ou a mais nova da conversa),
nunca até "agora": uma mensagem ainda em trânsito não fica entregue por engano.
Confirmações repetidas (várias abas, WebSocket e push da mesma mensagem) não
mudam nada nem geram aviso.

Quando um marcador avança, os outros participantes recebem em
`/user/queue/notifications`:

```json
{ "type": "RECEIPTS", "conversationId": "…", "receipts": { "deliveredAt": "…", "readAt": "…" } }
```

`GET /api/v1/chats` traz o mesmo `receipts` em cada conversa, do ponto de vista de
quem pergunta. Num grupo vale o participante mais atrasado (✓✓ só quando chegou a
todos) e `null` enquanto algum deles não tem o marcador. O cliente decide o tique
comparando o `createdAt` de cada mensagem com os dois instantes.

Os tiques são só metadados (ids e instantes que o servidor já tem): funcionam
igual com o E2EE, sem o servidor ver nada do conteúdo. Confirmação de conversa
alheia, inexistente ou de mensagem do próprio usuário é ignorada sem erro, como o foco.
