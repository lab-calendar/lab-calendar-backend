package com.labcalendar.labcalendarbackend.expense.importing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.labcalendar.labcalendarbackend.auth.CurrentSession;
import com.labcalendar.labcalendarbackend.common.api.ApiResponse;
import com.labcalendar.labcalendarbackend.common.exception.*;

@RestController
@RequestMapping("/api/card-expenses/imports")
public class CardImportController {
    private final CurrentSession session;
    private final ExcelLedgerReader reader;
    private final LedgerSheetSelector selector;
    private final LedgerRowParser parser;
    private final LedgerSyncService sync;
    private final PreviewTokenCodec tokens;
    public CardImportController(CurrentSession session, ExcelLedgerReader reader, LedgerSheetSelector selector,
            LedgerRowParser parser, LedgerSyncService sync, PreviewTokenCodec tokens) {
        this.session = session; this.reader = reader; this.selector = selector;
        this.parser = parser; this.sync = sync; this.tokens = tokens;
    }
    public record Totals(int added, int removed, int unchanged, long skippedRows, long blockedMonths) {}
    public record Problem(String sheet, int row, LedgerRowParser.Level level, String code, String message) {}
    public record Response(boolean dryRun, String previewToken, String fileName,
            List<LedgerSyncService.MonthResult> months, List<LedgerSheetSelector.SkippedSheet> skippedSheets,
            List<Problem> problems, Totals totals) {}

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<Response> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) String previewToken) {
        if (!session.seesCardData()) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (file.getSize() > ExcelLedgerReader.MAX_FILE_BYTES) throw new ImportApiException(413, "FILE_TOO_LARGE");
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(ExcelLedgerReader.MAX_FILE_BYTES + 1);
        } catch (IOException failure) { throw new ImportApiException(400, "INVALID_WORKBOOK"); }
        if (bytes.length > ExcelLedgerReader.MAX_FILE_BYTES) throw new ImportApiException(413, "FILE_TOO_LARGE");
        String original = file.getOriginalFilename();
        String fileName = original == null ? "" : original.replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (fileName.codePointCount(0, fileName.length()) > 191) throw new ImportApiException(400, "INVALID_FILE_NAME");
        final String label = fileName;
        String hash = PreviewTokenCodec.hash(bytes);
        return sync.withImportLock(() -> {
            // Parse again from the submitted bytes for apply, not from a cached preview result.
            var selection = selector.select(reader.read(label, bytes));
            var months = parser.parse(selection);
            String monthHash = PreviewTokenCodec.hash(months.stream().map(m -> m.month().toString()).sorted()
                    .collect(java.util.stream.Collectors.joining(",")).getBytes(StandardCharsets.UTF_8));
            LedgerSyncService.Result result;
            String token = null;
            if (dryRun) {
                var state = sync.previewState(months);
                result = state.result();
                token = tokens.issue(hash, state.fingerprint(), monthHash);
            } else {
                result = sync.applyVerified(label, months,
                        fingerprint -> tokens.verify(previewToken, hash, fingerprint, monthHash));
            }
            var problems = months.stream().flatMap(m -> m.problems().stream()).map(p -> new Problem(
                    p.sheet(), p.row(), p.level(), p.code().name(), p.level() == LedgerRowParser.Level.ERROR
                    ? "행을 확인해 주세요. 해당 월 전체를 유지합니다." : "참석자 정보가 없어 0명으로 반영합니다.")).toList();
            return ApiResponse.of(new Response(dryRun, token, label, result.months(), selection.skippedSheets(), problems,
                    new Totals(result.added(), result.removed(), result.unchanged(),
                            months.stream().mapToLong(LedgerRowParser.ParsedMonth::errorRowCount).sum(),
                            months.stream().filter(m -> m.status() == LedgerRowParser.Status.BLOCKED).count())));
        });
    }
}
