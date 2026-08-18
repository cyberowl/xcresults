package io.eroshenkoam.xcresults.export;

import com.fasterxml.jackson.databind.JsonNode;
import io.eroshenkoam.xcresults.util.HashUtil;
import io.qameta.allure.model.*;
import org.apache.commons.io.FilenameUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.eroshenkoam.xcresults.export.ExportProcessor.FILE_EXTENSION_HEIC;
import static io.eroshenkoam.xcresults.util.FormatUtil.getAttachmentFileName;
import static io.eroshenkoam.xcresults.util.FormatUtil.parseDate;
import static java.util.Objects.isNull;

public class Allure2ExportFormatter implements ExportFormatter {

    private static final String IDENTIFIER = "identifier";
    private static final String DURATION = "duration";
    private static final String STATUS = "testStatus";
    private static final String FAILURE_SUMMARIES = "failureSummaries";

    private static final String FAILURE_IS_TOP_LEVEL = "isTopLevelFailure";

    private static final String SKIP_NOTICE_SUMMARY = "skipNoticeSummary";
    private static final String ACTIVITY_SUMMARIES = "activitySummaries";
    private static final String ACTIVITY_TYPE = "activityType";
    private static final String ACTIVITY_UUID = "uuid";
    private static final String ACTIVITY_TITLE = "title";
    private static final String ACTIVITY_START = "start";
    private static final String ACTIVITY_FINISH = "finish";
    private static final String ACTIVITY_FAILURE_SUMMARY_IDS = "failureSummaryIDs";

    private static final String FAILURE_MESSAGE = "message";
    private static final String FAILURE_TIMESTAMP = "timestamp";

    private static final String SOURCE_CODE_CONTEXT = "sourceCodeContext";
    private static final String SYMBOL_INFO = "symbolInfo";
    private static final String CALL_STACK = "callStack";
    private static final String LOCATION = "location";
    private static final String FILE_PATH = "filePath";
    private static final String LINE_NUMBER = "lineNumber";

    private static final String SUBACTIVITIES = "subactivities";

    private static final String ATTACHMENTS = "attachments";

    private static final String NAME = "name";
    private static final String FILENAME = "filename";
    private static final String VALUE = "_value";
    private static final String VALUES = "_values";

    private static final String ARGUMENTS = "arguments";
    private static final String DESCRIPTION = "description";
    private static final String DEVICE = "device";
    private static final String LABEL = "label";
    private static final String OS = "os";
    private static final String PACKAGE = "package";
    private static final String PARAMETER = "parameter";
    private static final String PARAMETER_VALUE = "value";
    private static final String PLATFORM = "platform";
    private static final String SUB_SUITE = "subSuite";
    private static final String SUITE = "suite";
    private static final String TARGET = "testTarget";
    private static final String TEST_CLASS = "testClass";
    private static final String TEST_METHOD = "testMethod";

    private static final Pattern ALLURE_ID = Pattern.compile("allure\\.id:(?<id>.*)");
    private static final Pattern ALLURE_NAME = Pattern.compile("allure\\.name:(?<name>.*)");
    private static final Pattern ALLURE_DESCRIPTION = Pattern.compile("allure\\.description:(?<description>.*)");
    private static final Pattern ALLURE_LABEL = Pattern.compile("allure\\.label\\.(?<name>.*?):(?<value>.*)");
    private static final Pattern ALLURE_LINK = Pattern.compile("allure\\.link\\.(?<name>.*?)(|\\[(?<type>.*)]):(?<url>.*)");

    @Override
    public TestResult format(final ExportMeta meta, final JsonNode node) {
        final TestResult result = new TestResult()
                .setParameters(new ArrayList<>())
                .setLabels(new ArrayList<>())
                .setSteps(new ArrayList<>())
                .setAttachments(new ArrayList<>());
        if (node.has(NAME)) {
            result.setName(node.get(NAME).get(VALUE).asText());
        }
        if (node.has(IDENTIFIER)) {
            final String identifier = node.get(IDENTIFIER).get(VALUE).asText();
            final String historyId = getHistoryId(meta, identifier);
            result.setFullName(historyId);
            fillParameters(node, result);
            result.setTestCaseId(HashUtil.md5(historyId));
            result.setHistoryId(HashUtil.md5(getHistoryIdWithParameters(getHistoryIdWithEnvironment(historyId, meta), result)));
        }
        if (node.has(STATUS)) {
            result.setStatus(getTestStatus(node));
        }
        if (node.has(SKIP_NOTICE_SUMMARY)) {
            result.setStatus(Status.SKIPPED);
            final JsonNode skipNotice = node.get(SKIP_NOTICE_SUMMARY);
            if (skipNotice.has(FAILURE_MESSAGE)) {
                result.setStatusDetails(new StatusDetails().setMessage(skipNotice.get(FAILURE_MESSAGE)
                        .get(VALUE)
                        .asText()));
            }
        }
        final StepContext context = new StepContext()
                .setResult(result)
                .setCurrent(result)
                .setPath(Collections.singletonList(result));
        context.setFailures(new HashMap<>());
        if (node.has(FAILURE_SUMMARIES)) {
            node.get(FAILURE_SUMMARIES).get(VALUES).forEach(failure -> {
                final String key = failure.get(ACTIVITY_UUID).get(VALUE).asText();
                context.getFailures().put(key, failure);
            });
        }
        if (node.has(ACTIVITY_SUMMARIES)) {
            final Iterable<JsonNode> activities = node.get(ACTIVITY_SUMMARIES).get(VALUES);
            for (JsonNode activity : activities) {
                parseStep(activity, context);
            }
        }
        final Optional<StepResult> topLevelFailure = context.getFailures().values().stream()
                .filter(this::isTopLevelFailure)
                .map(this::getFailureStep)
                .findFirst();
        if (topLevelFailure.isPresent()) {
            final StepResult failStep = topLevelFailure.get();
            final List<StepResult> steps = context.getResult().getSteps();
            steps.add(getPosition(steps, failStep), failStep);
            result.setStatus(failStep.getStatus());
            result.setStatusDetails(failStep.getStatusDetails());
        }
        meta.getLabels().forEach((name, value) -> {
            result.getLabels().add(new Label().setName(name).setValue(value));
        });
        fillTestIdentityLabels(node, meta, result);
        if (Objects.isNull(result.getStart())) {
            result.setStart(meta.getStart());
        }
        if (Objects.nonNull(result.getStart())) {
            if (node.has(DURATION)) {
                final Double durationText = node.get(DURATION).get(VALUE).asDouble();
                long durationToMillis = (long) (durationText * 1000);
                result.setStop(result.getStart() + durationToMillis);
            }
            if (result.getSteps().size() > 0) {
                result.setStart(result.getSteps().get(0).getStart());
                result.setStop(result.getSteps().get(result.getSteps().size() - 1).getStop());
            }
        }
        return result;
    }

    @SuppressWarnings("PMD.NcssCount")
    private void parseStep(final JsonNode activity,
                           final StepContext context) {
        final Optional<String> title = getActivityTitle(activity);
        if (!title.isPresent()) {
            return;
        }
        final String activityTitle = title.get();

        final Matcher idMatcher = ALLURE_ID.matcher(activityTitle);
        if (idMatcher.matches()) {
            final Label label = new Label()
                    .setName("AS_ID")
                    .setValue(idMatcher.group("id"));
            context.getResult().getLabels().add(label);
            return;
        }
        final Matcher nameMatcher = ALLURE_NAME.matcher(activityTitle);
        if (nameMatcher.matches()) {
            context.getResult().setName(nameMatcher.group("name"));
            return;
        }
        final Matcher descriptionMatcher = ALLURE_DESCRIPTION.matcher(activityTitle);
        if (descriptionMatcher.matches()) {
            context.getResult().setDescription(descriptionMatcher.group("description"));
            return;
        }
        final Matcher labelMatcher = ALLURE_LABEL.matcher(activityTitle);
        if (labelMatcher.matches()) {
            final Label label = new Label()
                    .setName(labelMatcher.group("name"))
                    .setValue(labelMatcher.group("value").trim());
            context.getResult().getLabels().add(label);
            return;
        }
        final Matcher linkMatcher = ALLURE_LINK.matcher(activityTitle);
        if (linkMatcher.matches()) {
            final Link link = new Link()
                    .setName(linkMatcher.group("name"))
                    .setType(linkMatcher.group("type"))
                    .setUrl(linkMatcher.group("url").trim());
            context.getResult().getLinks().add(link);
            return;
        }

        final Optional<List<Attachment>> attachments = Optional.ofNullable(activity.get(ATTACHMENTS))
                .map(a -> a.get(VALUES))
                .map(this::getAttachments);
        if (activityTitle.startsWith("Start Test at") && activity.has(ACTIVITY_START)) {
            context.getResult().setStart(parseDate(activity.get(ACTIVITY_START).get(VALUE).asText()));
            attachments.ifPresent(context.getCurrent().getAttachments()::addAll);
            return;
        }

        final StepResult step = new StepResult()
                .setName(activityTitle)
                .setStatus(Status.PASSED)
                .setSteps(new ArrayList<>())
                .setAttachments(new ArrayList<>());
        attachments.ifPresent(step.getAttachments()::addAll);

        if (activity.has(ACTIVITY_START) && activity.has(ACTIVITY_FINISH)) {
            step.setStart(parseDate(activity.get(ACTIVITY_START).get(VALUE).asText()));
            step.setStop(parseDate(activity.get(ACTIVITY_FINISH).get(VALUE).asText()));
        }
        if (activity.has(SUBACTIVITIES)) {
            for (JsonNode subActivity : activity.get(SUBACTIVITIES).get(VALUES)) {
                parseStep(subActivity, context.child(step));
            }
        }
        if (activity.has(ACTIVITY_FAILURE_SUMMARY_IDS)) {
            final Iterable<JsonNode> activityFailures = activity.get(ACTIVITY_FAILURE_SUMMARY_IDS).get(VALUES);
            for (JsonNode activityFailureUuid : activityFailures) {
                final String uuid = activityFailureUuid.get(VALUE).asText();
                final StepResult failureStep = getFailureStep(context.getFailures().get(uuid));
                step.getSteps().add(failureStep);
                step.setStatus(failureStep.getStatus());
                step.setStatusDetails(failureStep.getStatusDetails());
                context.getPath().forEach(item -> {
                    item.setStatusDetails(failureStep.getStatusDetails());
                    item.setStatus(failureStep.getStatus());
                });
            }
        }
        context.getCurrent().getSteps().add(step);
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private List<Attachment> getAttachments(final Iterable<JsonNode> nodes) {
        final List<Attachment> attachments = new ArrayList<>();
        for (JsonNode node : nodes) {
            final String originalFileName = node.get(FILENAME).get(VALUE).asText();
            final String fileExtension = FilenameUtils.getExtension(originalFileName);
            final String sources = getAttachmentFileName(fileExtension);
            final String fileName = FILE_EXTENSION_HEIC.equals(fileExtension)
                    ? String.format("%s.%s", FilenameUtils.getBaseName(originalFileName), "jpeg")
                    : originalFileName;
            final Attachment attachment = new Attachment()
                    .setSource(sources)
                    .setName(fileName);
            attachments.add(attachment);
        }
        return attachments;
    }

    private int getPosition(final List<StepResult> steps, final StepResult step) {
        int position = 0;
        for (int i = 0; i < steps.size(); i++) {
            final StepResult prevStep = steps.get(i == 0 ? 0 : i - 1);
            final StepResult currStep = steps.get(i);
            if (prevStep.getStop() <= step.getStart() && step.getStop() <= currStep.getStart()) {
                position = i;
                break;
            }
        }
        return position;
    }

    private Status getTestStatus(final JsonNode node) {
        final String status = node.get(STATUS).get(VALUE).asText();
        if (isNull(status)) {
            return null;
        }

        switch (status) {
            case "Success":
            case "Expected Failure":
                return Status.PASSED;
            case "Failure":
                return Status.FAILED;
            case "Skipped":
                return Status.SKIPPED;
            default:
                return null;
        }
    }

    private Optional<String> getActivityTitle(final JsonNode node) {
        if (node.has(ACTIVITY_TITLE)) {
            return Optional.of(node.get(ACTIVITY_TITLE).get(VALUE).asText());
        }
        if (node.has(ACTIVITY_TYPE)) {
            return Optional.of(node.get(ACTIVITY_TYPE).get(VALUE).asText());
        }
        return Optional.empty();
    }

    private Boolean isTopLevelFailure(final JsonNode activityFailure) {
        if (activityFailure.has(FAILURE_IS_TOP_LEVEL)) {
            return activityFailure.get(FAILURE_IS_TOP_LEVEL).get(VALUE).asBoolean();
        }
        return false;
    }

    private StepResult getFailureStep(final JsonNode activityFailure) {
        final Long timestamp = parseDate(activityFailure.get(FAILURE_TIMESTAMP).get(VALUE).asText());
        final String message = activityFailure.get(FAILURE_MESSAGE).get(VALUE).asText();
        final String trace = getStackTrace(activityFailure);
        final Status failedStatus = Status.FAILED;
        final StatusDetails failedDetails = new StatusDetails()
                .setMessage(message)
                .setTrace(trace);
        final StepResult failureStep = new StepResult()
                .setStatus(failedStatus)
                .setName(message)
                .setStart(timestamp)
                .setStop(timestamp);
        failureStep.setStatusDetails(failedDetails);
        if (activityFailure.has(ATTACHMENTS)) {
            failureStep.getAttachments().addAll(getAttachments(activityFailure.get(ATTACHMENTS).get(VALUES)));
        }
        return failureStep;
    }

    private String getStackTrace(final JsonNode activityFailure) {
        if (activityFailure.has(SOURCE_CODE_CONTEXT)) {
            final JsonNode context = activityFailure.get(SOURCE_CODE_CONTEXT);
            if (context.has(CALL_STACK)) {
                final List<String> lines = new ArrayList<>();
                for (JsonNode line : context.findValue(CALL_STACK).get(VALUES)) {
                    Optional.ofNullable(line.get(SYMBOL_INFO))
                            .map(v -> v.findValue(LOCATION))
                            .flatMap(this::getFileLineNumber)
                            .ifPresent(lines::add);
                }
                return String.join("\n", lines);
            }
        }
        return null;
    }

    private Optional<String> getFileLineNumber(final JsonNode location) {
        if (location.has(FILE_PATH) && location.has(LINE_NUMBER)) {
            final String filePath = location.get(FILE_PATH).get(VALUE).asText();
            final String lineNumber = location.get(LINE_NUMBER).get(VALUE).asText();
            return Optional.of(String.format("%s:%s", filePath, lineNumber));
        }
        return Optional.empty();
    }

    private String getHistoryIdWithEnvironment(final String historyId, final ExportMeta meta) {
        final List<String> env = new ArrayList<>();
        final Map<String, String> labels = meta.getLabels();
        if (labels.containsKey(OS)) {
            env.add(labels.get(OS));
        }
        if (labels.containsKey(DEVICE)) {
            env.add(labels.get(DEVICE));
        }
        if (labels.containsKey(PLATFORM)) {
            env.add(labels.get(PLATFORM));
        }
        return env.isEmpty() ? historyId : String.format("%s[%s]", historyId, String.join(", ", env));
    }

    private void fillTestIdentityLabels(final JsonNode node, final ExportMeta meta, final TestResult result) {
        final Set<String> labelNames = new HashSet<>();
        result.getLabels().forEach(label -> labelNames.add(label.getName()));
        if (node.has(IDENTIFIER)) {
            final String identifier = node.get(IDENTIFIER).get(VALUE).asText();
            final String[] segments = identifier.split("/");
            if (segments.length >= 2) {
                final String suite = segments[0];
                final String testClass = segments[segments.length - 2];
                final String testMethod = stripTestArguments(segments[segments.length - 1]);
                addLabel(result, labelNames, SUITE, suite);
                addLabel(result, labelNames, TEST_CLASS, testClass);
                addLabel(result, labelNames, TEST_METHOD, testMethod);
                if (segments.length >= 4) {
                    addLabel(result, labelNames, SUB_SUITE, segments[1]);
                }
                final String target = meta.getLabels().get(TARGET);
                addLabel(result, labelNames, PACKAGE, isNull(target) ? suite : target);
            }
        }
    }

    private void addLabel(final TestResult result, final Set<String> labelNames, final String name, final String value) {
        if (labelNames.add(name)) {
            result.getLabels().add(new Label().setName(name).setValue(value));
        }
    }

    private String stripTestArguments(final String method) {
        final int paren = method.indexOf('(');
        if (paren < 0 || method.indexOf(')', paren) < 0) {
            return method;
        }
        return method.substring(0, paren);
    }

    private void fillParameters(final JsonNode node, final TestResult result) {
        if (node.has(ARGUMENTS)) {
            node.get(ARGUMENTS).get(VALUES).forEach(argument -> {
                final String name = argument.get(PARAMETER).get(LABEL).get(VALUE).asText();
                final String value = argument.get(PARAMETER_VALUE).get(DESCRIPTION).get(VALUE).asText();
                result.getParameters().add(new Parameter().setName(name).setValue(value));
            });
        }
    }

    private String getHistoryIdWithParameters(final String historyId, final TestResult result) {
        if (result.getParameters().isEmpty()) {
            return historyId;
        }
        final List<String> params = new ArrayList<>();
        result.getParameters().forEach(p -> params.add(p.getName() + "=" + p.getValue()));
        return String.format("%s[%s]", historyId, String.join(",", params));
    }

    private class StepContext {

        private TestResult result;
        private ExecutableItem current;
        private List<ExecutableItem> path;
        private Map<String, JsonNode> failures;

        public TestResult getResult() {
            return result;
        }

        public StepContext setResult(TestResult result) {
            this.result = result;
            return this;
        }

        public ExecutableItem getCurrent() {
            return current;
        }

        public StepContext setCurrent(ExecutableItem current) {
            this.current = current;
            return this;
        }

        public List<ExecutableItem> getPath() {
            return path;
        }

        public StepContext setPath(List<ExecutableItem> path) {
            this.path = path;
            return this;
        }

        public Map<String, JsonNode> getFailures() {
            return this.failures;
        }

        public StepContext setFailures(final Map<String, JsonNode> failures) {
            this.failures = failures;
            return this;
        }

        public StepContext child(final ExecutableItem next) {
            final List<ExecutableItem> nextPath = new ArrayList<>(path);
            nextPath.add(next);
            return new StepContext()
                    .setResult(result)
                    .setCurrent(next)
                    .setPath(nextPath)
                    .setFailures(this.getFailures());

        }
    }

    private String getHistoryId(final ExportMeta meta, final String identifier) {
        final String suite = meta.getLabels().getOrDefault(TARGET, "Default");
        return String.format("%s/%s", suite, identifier);
    }

}
