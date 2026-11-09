package br.dev.duofinance.shared.config;

import java.lang.reflect.RecordComponent;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Finds the API response records: every record under a {@code ..dto} package (or {@code shared.web}) that is not a
 * request or a filter. Nested records (e.g. {@code TransactionResponse.AuthorRef}) are included.
 */
public final class ResponseRecords {

    private static final String BASE_PACKAGE = "br.dev.duofinance";

    private ResponseRecords() {}

    public static List<Class<?>> all() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(bd -> ClassUtils.resolveClassName(bd.getBeanClassName(), ResponseRecords.class.getClassLoader()))
                .filter(ResponseRecords::isResponse)
                .sorted(java.util.Comparator.comparing(Class::getName))
                .<Class<?>>map(c -> c)
                .toList();
    }

    private static boolean isResponse(Class<?> type) {
        String pkg = type.getPackageName();
        String name = type.getSimpleName();
        boolean inDto = pkg.endsWith(".dto") || pkg.equals(BASE_PACKAGE + ".shared.web");
        return type.isRecord() && inDto && !name.endsWith("Request") && !name.endsWith("Filter");
    }

    public static boolean isNullable(RecordComponent component) {
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class);
    }
}
