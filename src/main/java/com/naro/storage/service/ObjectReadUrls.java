package com.naro.storage.service;

import org.mapstruct.Named;
import org.springframework.stereotype.Component;

/**
 * Converte a chave de um objeto na URL de leitura pré-assinada, para os
 * mappers que expõem imagens. Sem imagem (chave nula), a URL também é nula.
 */
@Component
public class ObjectReadUrls {

    private final ObjectStorageService storageService;

    public ObjectReadUrls(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    @Named("readUrl")
    public String readUrl(String objectKey) {
        return objectKey == null ? null : storageService.createReadUrl(objectKey);
    }
}
