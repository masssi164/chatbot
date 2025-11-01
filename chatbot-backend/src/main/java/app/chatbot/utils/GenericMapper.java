package app.chatbot.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
public class GenericMapper {

    private final ObjectMapper objectMapper;

    public GenericMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T map(Object source, Class<T> targetType) {
        if (source == null) {
            return null;
        }
        return objectMapper.convertValue(source, targetType);
    }

    public <S, T> List<T> mapList(Collection<S> source, Class<T> targetType) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        CollectionType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, targetType);
        return objectMapper.convertValue(new ArrayList<>(source), listType);
    }
}
