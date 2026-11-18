package br.dev.duofinance.support;

import br.dev.duofinance.imports.ai.MerchantCategorizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Test categorizer: records every call and answers from a preset function (empty by default). */
public class RecordingMerchantCategorizer implements MerchantCategorizer {

    public record Call(UUID accountId, List<Merchant> merchants, List<CategoryOption> categories) {}

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Call, Map<String, UUID>> answer = c -> Map.of();

    @Override
    public Map<String, UUID> categorize(UUID accountId, List<Merchant> merchants, List<CategoryOption> categories) {
        Call call = new Call(accountId, List.copyOf(merchants), List.copyOf(categories));
        calls.add(call);
        return new HashMap<>(answer.apply(call));
    }

    public void answerWith(Function<Call, Map<String, UUID>> answer) {
        this.answer = answer;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public void reset() {
        calls.clear();
        answer = c -> Map.of();
    }
}
