package com.edsp.transform.standardevent;

import com.edsp.transform.standardevent.dedup.DedupKeyBuilder;
import com.edsp.transform.standardevent.normalize.RiskScoreCalculator;
import com.edsp.transform.standardevent.normalize.SeverityNormalizer;
import com.edsp.transform.standardevent.normalize.TimeValueParser;

public class StandardEventTransformService {
    private final StandardEventTransformRuleProcessor ruleProcessor;

    public StandardEventTransformService() {
        this(new TimeValueParser(), new SeverityNormalizer(), new RiskScoreCalculator(), new DedupKeyBuilder());
    }

    StandardEventTransformService(
        TimeValueParser timeValueParser,
        SeverityNormalizer severityNormalizer,
        RiskScoreCalculator riskScoreCalculator,
        DedupKeyBuilder dedupKeyBuilder
    ) {
        this(new StandardEventTransformRuleProcessor(
            timeValueParser,
            severityNormalizer,
            riskScoreCalculator,
            dedupKeyBuilder
        ));
    }

    StandardEventTransformService(StandardEventTransformRuleProcessor ruleProcessor) {
        this.ruleProcessor = ruleProcessor == null ? new StandardEventTransformRuleProcessor() : ruleProcessor;
    }

    public TransformResult transform(SourceRow row, MappingPlan plan, TransformOptions options) {
        return ruleProcessor.process(row, plan, options);
    }
}
