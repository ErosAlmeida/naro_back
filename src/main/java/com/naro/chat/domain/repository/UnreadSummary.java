package com.naro.chat.domain.repository;

/** Total de não lidas de um usuário, em todas as conversas dele. */
public interface UnreadSummary {
    long getConversations();
    long getMessages();
}
