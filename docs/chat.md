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

A fila privada `/user/queue/errors` retorna `FRIENDSHIP_REQUIRED` quando não existe
mais amizade autorizando o envio. O evento contém `conversationId` e, para mensagens,
o `clientMessageId` enviado pelo cliente. Isso permite que a interface interrompa o
envio e marque apenas a mensagem recusada. Eventos de digitação não têm
`clientMessageId`. Os outros erros preservam `CHAT_ERROR` e `INVALID_PAYLOAD`.
