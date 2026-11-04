package dev.plex.ecc.safety;

import com.google.gson.JsonObject;
import io.airlift.compress.v3.zstd.ZstdJavaOutputStream;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The day's record: one JSON line per captured message and per decision, in {@code YYYY-MM-DD.jsonl} (UTC days).
 * At midnight the day's file is compressed to {@code YYYY-MM-DD.jsonl.zst} and files past retention are deleted.
 * Every file operation runs on the record's own writer thread, so lines keep their order.
 */
final class RecordLog
{
    private static final Pattern RECORD_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.jsonl(\\.zst)?");

    private final Path folder;
    private final String server;
    private final int retentionDays;
    private final Logger logger;
    private final Clock clock = Clock.systemUTC();
    private final ScheduledThreadPoolExecutor writer =
            new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("Plex-ECC-Record").factory());
    // Accessed on the writer thread only.
    private @Nullable LocalDate day;
    private @Nullable BufferedWriter out;

    RecordLog(Path folder, String server, int retentionDays, Logger logger)
    {
        this.folder = folder;
        this.server = server;
        this.retentionDays = retentionDays;
        this.logger = logger;
        writer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    // Compresses files left by an earlier run, applies retention, and schedules the midnight rotation.
    void start() throws IOException
    {
        Files.createDirectories(folder);
        writer.execute(this::rotate);
        scheduleMidnight();
    }

    void append(JsonObject line)
    {
        line.addProperty("server", server);
        String text = line.toString();
        try
        {
            writer.execute(() -> write(text));
        }
        catch (RejectedExecutionException ex)
        {
            logger.warn("Chat safety stopped before it could record: {}", text);
        }
    }

    // Writes every line already appended, then closes the day's file.
    void close()
    {
        writer.execute(this::closeFile);
        writer.shutdown();
        try
        {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS))
            {
                logger.error("The chat safety record did not finish writing in time; its last lines are lost");
                writer.shutdownNow();
            }
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    private void write(String text)
    {
        try
        {
            LocalDate today = LocalDate.now(clock);
            if (!today.equals(day))
            {
                rotate();
                out = Files.newBufferedWriter(folder.resolve(today + ".jsonl"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                day = today;
            }
            out.write(text);
            out.write('\n');
            // A crash loses at most the line being written.
            out.flush();
        }
        catch (IOException ex)
        {
            logger.error("Unable to write the chat safety record", ex);
            closeFile();
        }
    }

    private void scheduleMidnight()
    {
        long midnight = LocalDate.now(clock).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        writer.schedule(() ->
        {
            rotate();
            scheduleMidnight();
        }, midnight - clock.millis() + 1000, TimeUnit.MILLISECONDS);
    }

    // Closes a file of an earlier day, compresses every day file before today, and deletes compressed files past
    // retention.
    private void rotate()
    {
        LocalDate today = LocalDate.now(clock);
        if (day != null && !day.equals(today))
        {
            closeFile();
        }
        LocalDate oldest = today.minusDays(retentionDays);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder))
        {
            for (Path file : files)
            {
                Matcher name = RECORD_FILE.matcher(file.getFileName().toString());
                if (name.matches())
                {
                    rotate(file, LocalDate.parse(name.group(1)), name.group(2) != null, today, oldest);
                }
            }
        }
        catch (IOException ex)
        {
            logger.error("Unable to list the chat safety records in {}", folder, ex);
        }
    }

    private void rotate(Path file, LocalDate date, boolean compressed, LocalDate today, LocalDate oldest)
    {
        try
        {
            if (compressed && date.isBefore(oldest))
            {
                Files.delete(file);
            }
            else if (!compressed && date.isBefore(today))
            {
                Path partial = folder.resolve(file.getFileName() + ".zst.part");
                try (InputStream in = Files.newInputStream(file);
                     OutputStream zst = new ZstdJavaOutputStream(Files.newOutputStream(partial)))
                {
                    in.transferTo(zst);
                }
                Files.move(partial, folder.resolve(file.getFileName() + ".zst"), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                Files.delete(file);
            }
        }
        catch (IOException ex)
        {
            logger.error("Unable to compress or delete the chat safety record {}", file, ex);
        }
    }

    private void closeFile()
    {
        if (out == null)
        {
            return;
        }
        try
        {
            out.close();
        }
        catch (IOException ex)
        {
            logger.error("Unable to close the chat safety record", ex);
        }
        out = null;
        day = null;
    }
}
