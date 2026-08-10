package io.eroshenkoam.xcresults.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import freemarker.template.Version;
import io.eroshenkoam.xcresults.broken.BrokenPostProcessor;
import io.eroshenkoam.xcresults.carousel.CarouselPostProcessor;
import io.qameta.allure.model.ExecutableItem;
import io.qameta.allure.model.TestResult;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.eroshenkoam.xcresults.util.FormatUtil.getResultFilePath;
import static io.eroshenkoam.xcresults.util.FormatUtil.parseDate;
import static io.eroshenkoam.xcresults.util.ProcessUtil.*;

public class ExportProcessor {

    public static final String FILE_EXTENSION_HEIC = "heic";

    private static final String ACTIONS = "actions";
    private static final String ACTION_RESULT = "actionResult";

    private static final String RUN_DESTINATION = "runDestination";
    private static final String START_TIME = "startedTime";

    private static final String SUMMARIES = "summaries";
    private static final String TESTABLE_SUMMARIES = "testableSummaries";

    private static final String TESTS = "tests";
    private static final String SUBTESTS = "subtests";

    private static final String FAILURE_SUMMARIES = "failureSummaries";
    private static final String ACTIVITY_SUMMARIES = "activitySummaries";
    private static final String SUBACTIVITIES = "subactivities";

    private static final String ATTACHMENTS = "attachments";

    private static final String FILENAME = "filename";
    private static final String PAYLOAD_REF = "payloadRef";

    private static final String SUMMARY_REF = "summaryRef";

    private static final String SUITE = "suite";

    private static final String ID = "id";
    private static final String TYPE = "_type";
    private static final String NAME = "_name";
    private static final String VALUE = "_value";
    private static final String VALUES = "_values";
    private static final String DISPLAY_NAME = "displayName";
    private static final String TARGET_NAME = "targetName";

    private static final String TEST_REF = "testsRef";

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final Path inputPath;
    private final Path outputPath;

    private final String brokenConfigPath;

    private final Boolean addCarouselAttachment;
    private final String carouselTemplatePath;


    public ExportProcessor(final Path inputPath,
                           final Path outputPath,
                           final String brokenConfigPath,
                           final Boolean addCarouselAttachment,
                           final String carouselTemplatePath) {
        this.inputPath = inputPath;
        this.outputPath = outputPath;
        this.brokenConfigPath = brokenConfigPath;
        this.addCarouselAttachment = addCarouselAttachment;
        this.carouselTemplatePath = carouselTemplatePath;
    }

    public void export() throws Exception {
        final JsonNode node = readSummary();

        final Map<String, ExportMeta> testRefIds = new HashMap<>();
        for (JsonNode action : node.get(ACTIONS).get(VALUES)) {
            if (action.get(ACTION_RESULT).has(TEST_REF)) {
                final ExportMeta meta = new ExportMeta();
                if (action.has(RUN_DESTINATION)) {
                    meta.label(RUN_DESTINATION, action.get(RUN_DESTINATION).get(DISPLAY_NAME).get(VALUE).asText());
                }
                if (action.has(START_TIME)) {
                    meta.setStart(parseDate(action.get(START_TIME).get(VALUE).textValue()));
                }
                testRefIds.put(action.get(ACTION_RESULT).get(TEST_REF).get(ID).get(VALUE).asText(), meta);
            }
        }

        final ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        final Map<String, JsonNode> references = new ConcurrentHashMap<>();
        final List<SummaryItem> summaryItems = Collections.synchronizedList(new ArrayList<>());
        final Map<JsonNode, ExportMeta> testSummaries = new ConcurrentHashMap<>();
        final Map<String, String> attachmentsRefs = new ConcurrentHashMap<>();
        final Map<Path, TestResult> testResults = new ConcurrentHashMap<>();
        try {
            runInParallel(testRefIds.keySet(), testRefId ->
                    references.put(testRefId, getReference(testRefId)), executor);

            runInParallel(testRefIds.entrySet(), entry -> {
                final JsonNode testRef = references.get(entry.getKey());
                for (JsonNode summary : testRef.get(SUMMARIES).get(VALUES)) {
                    for (JsonNode testableSummary : summary.get(TESTABLE_SUMMARIES).get(VALUES)) {
                        final ExportMeta testMeta = getTestMeta(entry.getValue(), testableSummary);
                        if (testableSummary.has(TESTS) && testableSummary.get(TESTS).has(VALUES)) {
                            for (JsonNode test : testableSummary.get(TESTS).get(VALUES)) {
                                collectSummaryItems(test, testMeta, summaryItems);
                            }
                        } else {
                            System.out.printf("No tests found for '%s'%n", testableSummary.get("name").get(VALUE));
                        }
                    }
                }
            }, executor);

            final Set<String> summaryRefIds = summaryItems.stream()
                    .map(item -> item.refId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            runInParallel(summaryRefIds, summaryRefId ->
                    references.put(summaryRefId, getReference(summaryRefId)), executor);

            runInParallel(summaryItems, item -> {
                final JsonNode testSummary = Objects.nonNull(item.refId)
                        ? references.get(item.refId)
                        : item.inlineSummary;
                testSummaries.put(testSummary, item.meta);
            }, executor);

            System.out.printf("Export information about %s test summaries...%n", testSummaries.size());
            runInParallel(testSummaries.entrySet(), entry -> {
                final JsonNode testSummary = entry.getKey();
                final ExportMeta meta = entry.getValue();

                final TestResult testResult = new Allure2ExportFormatter().format(meta, testSummary);
                final Path testSummaryPath = getResultFilePath(outputPath);
                try {
                    mapper.writeValue(testSummaryPath.toFile(), testResult);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                final Map<String, List<String>> attachmentSources = getAttachmentSources(testResult);
                final List<JsonNode> summaries = new ArrayList<>();
                summaries.addAll(getAttributeValues(testSummary, ACTIVITY_SUMMARIES));
                summaries.addAll(getAttributeValues(testSummary, FAILURE_SUMMARIES));
                summaries.forEach(summary -> {
                    getAttachmentRefs(summary).forEach((name, ref) -> {
                        if (attachmentSources.containsKey(name)) {
                            final List<String> sources = attachmentSources.get(name);
                            sources.forEach(source -> attachmentsRefs.put(source, ref));
                        }
                    });
                });
                testResults.put(testSummaryPath, testResult);
            }, executor);
            exportAttachments(attachmentsRefs, executor);
        } finally {
            executor.shutdown();
        }

        final List<ExportPostProcessor> postProcessors = new ArrayList<>();
        if (Objects.nonNull(addCarouselAttachment)) {
            postProcessors.add(new CarouselPostProcessor(carouselTemplatePath));
        }
        if (Objects.nonNull(brokenConfigPath)) {
            postProcessors.add(new BrokenPostProcessor(brokenConfigPath));
        }
        postProcessors.forEach(
                postProcessor -> postProcessor.processTestResults(outputPath, testResults)
        );
    }

    private ExportMeta getTestMeta(final ExportMeta meta, final JsonNode testableSummary) {
        final ExportMeta exportMeta = new ExportMeta();
        exportMeta.setStart(meta.getStart());
        meta.getLabels().forEach(exportMeta::label);
        exportMeta.label(SUITE, testableSummary.get(TARGET_NAME).get(VALUE).asText());
        return exportMeta;
    }

    private Map<String, List<String>> getAttachmentSources(final ExecutableItem executableItem) {
        final Map<String, List<String>> attachments = new HashMap<>();
        if (Objects.nonNull(executableItem.getAttachments())) {
            executableItem.getAttachments().forEach(a -> {
                final List<String> sources = attachments.getOrDefault(a.getName(), new ArrayList<>());
                sources.add(a.getSource());
                attachments.put(a.getName(), sources);
            });
        }
        if (Objects.nonNull(executableItem.getSteps())) {
            executableItem.getSteps().forEach(s -> attachments.putAll(getAttachmentSources(s)));
        }
        return attachments;
    }

    private Map<String, String> getAttachmentRefs(final JsonNode test) {
        final Map<String, String> refs = new HashMap<>();
        if (test.has(ATTACHMENTS)) {
            for (final JsonNode attachment : test.get(ATTACHMENTS).get(VALUES)) {
                if (attachment.has(PAYLOAD_REF)) {
                    final String fileName = attachment.get(FILENAME).get(VALUE).asText();
                    final String attachmentRef = attachment.get(PAYLOAD_REF).get(ID).get(VALUE).asText();
                    refs.put(fileName, attachmentRef);
                }
            }
        }
        if (test.has(SUBACTIVITIES)) {
            for (final JsonNode subActivity : test.get(SUBACTIVITIES).get(VALUES)) {
                refs.putAll(getAttachmentRefs(subActivity));
            }
        }
        return refs;
    }

    private void collectSummaryItems(final JsonNode test,
                                     final ExportMeta meta,
                                     final List<SummaryItem> items) {
        if (test.has(SUMMARY_REF)) {
            final String ref = test.get(SUMMARY_REF).get(ID).get(VALUE).asText();
            items.add(new SummaryItem(ref, null, meta));
        } else {
            if (test.has(TYPE) && test.get(TYPE).get(NAME).textValue().equals("ActionTestMetadata")) {
                items.add(new SummaryItem(null, test, meta));
            }
        }

        if (test.has(SUBTESTS)) {
            for (final JsonNode subTest : test.get(SUBTESTS).get(VALUES)) {
                collectSummaryItems(subTest, meta, items);
            }
        }
    }

    private static class SummaryItem {

        private final String refId;
        private final JsonNode inlineSummary;
        private final ExportMeta meta;

        private SummaryItem(final String refId, final JsonNode inlineSummary, final ExportMeta meta) {
            this.refId = refId;
            this.inlineSummary = inlineSummary;
            this.meta = meta;
        }

    }

    private List<JsonNode> getAttributeValues(final JsonNode node, final String attributeName) {
        final List<JsonNode> result = new ArrayList<>();
        if (node.has(attributeName) && node.get(attributeName).has(VALUES)) {
            node.get(attributeName).get(VALUES).forEach(result::add);
        }
        return result;
    }

    private static final boolean LEGACY_MODE = detectLegacyMode();

    private static boolean detectLegacyMode() {
        try {
            final String output = readProcessOutputAsString(new ProcessBuilder("xcodebuild", "-version"));
            final String versionLine = output.split("\n")[0];
            final Version version = new Version(versionLine.replaceFirst("Xcode ", "").trim());
            return version.getMajor() >= 16;
        } catch (final Exception e) {
            return false;
        }
    }

    private ProcessBuilder processBuilderForXCResultToolCommand(String... command) {
        final ProcessBuilder builder = new ProcessBuilder();
        builder.command(command);
        builder.command().add(0, "xcrun");
        builder.command().add(1, "xcresulttool");
        if (LEGACY_MODE) {
            builder.command().add("--legacy");
        }
        return builder;
    }

    private JsonNode readSummary() {
        final ProcessBuilder builder = processBuilderForXCResultToolCommand(
                "get",
                "--format", "json",
                "--path", inputPath.toAbsolutePath().toString()
        );
        return readProcessOutputAsJson(builder, mapper);
    }

    private JsonNode getReference(final String id) {
        final ProcessBuilder builder = processBuilderForXCResultToolCommand(
                "get",
                "--format", "json",
                "--path", inputPath.toAbsolutePath().toString(),
                "--id", id
        );
        return readProcessOutputAsJson(builder, mapper);
    }

    private void exportAttachments(final Map<String, String> attachmentsRefs, final ExecutorService executor) {
        final Map<String, List<String>> sourcesByRef = attachmentsRefs.entrySet()
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        System.out.printf("Export information about %s attachments (%s unique)...%n",
                attachmentsRefs.size(), sourcesByRef.size());
        runInParallel(sourcesByRef.entrySet(), entry -> exportAttachment(entry.getKey(), entry.getValue()), executor);
    }

    private void exportAttachment(final String ref, final List<String> sources) {
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("xcresults-", ".payload");
            exportPayload(ref, tempPath);
            for (final String source : sources) {
                copyPayloadToSource(tempPath, source);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (!Objects.isNull(tempPath)) {
                tempPath.toFile().delete();
            }
        }
    }

    private void copyPayloadToSource(final Path tempPath, final String source) throws IOException {
        final Path target = outputPath.resolve(source);
        Files.copy(tempPath, target, StandardCopyOption.REPLACE_EXISTING);
        convertHeicIfNeeded(target);
    }

    private void convertHeicIfNeeded(final Path target) {
        if (FILE_EXTENSION_HEIC.equals(FilenameUtils.getExtension(target.toString()))) {
            convertHeicToJpeg(target);
        }
    }

    private void exportPayload(final String id, final Path output) {
        final ProcessBuilder exportBuilder = processBuilderForXCResultToolCommand(
                "export",
                "--type", "file",
                "--path", inputPath.toAbsolutePath().toString(),
                "--id", id,
                "--output-path", output.toAbsolutePath().toString()
        );

        readProcessOutput(exportBuilder, (i) -> null);
    }

    private static Process getProcess(Path heicPath, Path parent, String jpegFilename) throws IOException {
        final Path jpegFilePath = parent.resolve(jpegFilename);
        final ProcessBuilder convertBuilder = new ProcessBuilder();
        convertBuilder.command(
                "sips", "-s",
                "format", "jpeg",
                heicPath.toAbsolutePath().toString(),
                "--out", jpegFilePath.toAbsolutePath().toString()
        );
        return convertBuilder.start();
    }

    private void convertHeicToJpeg(Path heicPath) {
        try {
            final Path parent = heicPath.getParent();
            final String jpegFilename = String.format("%s.%s", FilenameUtils.getBaseName(heicPath.toString()), "jpeg");
            Process process = getProcess(heicPath, parent, jpegFilename);
            process.waitFor();
            FileUtils.deleteQuietly(heicPath.toFile());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> void runInParallel(final Collection<T> items,
                                          final Consumer<T> action,
                                          final ExecutorService executor) {
        CompletableFuture.allOf(items.stream().map(item -> CompletableFuture.runAsync(() -> action.accept(item), executor)).toArray(CompletableFuture[]::new)).join();
    }

}
