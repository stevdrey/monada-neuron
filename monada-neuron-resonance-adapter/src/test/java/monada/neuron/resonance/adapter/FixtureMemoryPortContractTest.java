package monada.neuron.resonance.adapter;

import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;

import java.util.ArrayList;
import java.util.List;

/** Runs the contract against a deterministic fixture port, mirroring the core test adapter. */
final class FixtureMemoryPortContractTest extends ResonanceMemoryPortContractTest {

    @Override
    ResonanceMemoryPort port() {
        return request -> {
            var results = new ArrayList<ResonanceMemoryResult>();
            if (request.querySignals().contains(StoreFixtures.SOLAR)
                    || request.querySignals().contains(StoreFixtures.OCEAN)) {
                for (var index = 0; index < request.maxResults() && index < 3; index++) {
                    results.add(new ResonanceMemoryResult(
                            "fixture-" + index, StoreFixtures.signal(10.0 + index), 1.0 - index * 0.1));
                }
            }
            return new ResonanceMemoryResponse(
                    ResonanceMemoryStatus.COMPLETE, request.maxResults(), List.copyOf(results));
        };
    }
}
