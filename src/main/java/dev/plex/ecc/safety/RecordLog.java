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
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The day's record: one JSON line per captured message and per decision, in {@code YYYY-MM-DD.jsonl} (UTC days).
 * At midnight the day's file is compressed to {@code YYYY-MM-DD.jsonl.zst} and files past retention are deleted.
 * Every file operation runs on the chat safety I/O executor, so lines keep their order.
 */
final class RecordLog
{
    private static final String OPEN_SUFFIX = ".jsonl";
    private static final String CLOSED_SUFFIX = ".jsonl.zst";

    private final Path folder;
    private final String server;
    private final int retentionDays;
    private final ScheduledExecutorService io;
    private final Logger logger;
    private final Clock clock = Clock.systemUTC();
    // Accessed on the I/O executor only.
    private @Nullable LocalDate day;
    private @Nullable BufferedWriter writer;

    RecordLog(Path folder, String server, int retentionDays, ScheduledExecutorService io, Logger logger)
    {
        this.folder = folder;
        this.server = server;
        this.retentionDays = retentionDays;
        this.io = io;
        this.logger = logger;
    }

    // Closes files left open by an earlier run, applies retention, and schedules the midnight rotation.
    void start() throws IOException
    {
        Files.createDirectories(folder);
        io.execute(this::rotate);
        scheduleMidnight();
    }

    void append(JsonObject line)
    {
        line.addProperty("server", server);
        String text = line.toString();
        io.execute(() -> write(text));
    }

    void close()
    {
        io.execute(this::closeWriter);
    }

    private void write(String text)
    {
        try
        {
            LocalDate today = LocalDate.now(clock);
            if (!today.equals(day))
            {
                rotate();
            }
            if (writer == null)
            {
                writer = Files.newBufferedWriter(file(today, OPEN_SUFFIX), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                day = today;
            }
            writer.write(text);
            writer.write('\n');
            // A crash loses at most the line being written.
            writer.flush();
        }
        catch (IOException ex)
        {
            logger.error("Unable to write the chat safety record", ex);
            closeWriter();
        }
    }

    private void scheduleMidnight()
    {
        long now = clock.millis();
        long midnight = LocalDate.now(clock).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        io.schedule(() ->
        {
            rotate();
            scheduleMidnight();
        }, Math.max(0, midnight - now) + Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
    }

    // Compresses every day file before today and deletes compressed files past retention.
    private void rotate()
    {
        LocalDate today = LocalDate.now(clock);
        if (day != null && !day.equals(today))
        {
            closeWriter();
        }
        LocalDate oldest = today.minusDays(retentionDays);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder))
        {
            for (Path file : files)
            {
                rotate(file, today, oldest);
            }
        }
        catch (IOException ex)
        {
            logger.error("Unable to list the chat safety records in {}", folder, ex);
        }
    }

    private void rotate(Path file, LocalDate today, LocalDate oldest)
    {
        String name = file.getFileName().toString();
        try
        {
            if (name.endsWith(CLOSED_SUFFIX))
            {
                if (LocalDate.parse(name.substring(0, name.length() - CLOSED_SUFFIX.length())).isBefore(oldest))
                {
                    Files.delete(file);
                }
            }
            else if (name.endsWith(OPEN_SUFFIX)
                    && LocalDate.parse(name.substring(0, name.length() - OPEN_SUFFIX.length())).isBefore(today))
            {
                compress(file, folder.resolve(name + ".zst"));
            }
        }
        catch (DateTimeParseException ex)
        {
            // Not a record file.
        }
        catch (IOException ex)
        {
            logger.error("Unable to compress or delete the chat safety record {}", file, ex);
        }
    }

    private void compress(Path source, Path target) throws IOException
    {
        Path partial = folder.resolve(target.getFileName() + ".part");
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = new ZstdJavaOutputStream(Files.newOutputStream(partial)))
        {
            in.transferTo(out);
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.delete(source);
    }

    private void closeWriter()
    {
        if (writer == null)
        {
            return;
        }
        try
        {
            writer.close();
        }
        catch (IOException ex)
        {
            logger.error("Unable to close the chat safety record", ex);
        }
        writer = null;
        day = null;
    }

    private Path file(LocalDate date, String suffix)
    {
        return folder.resolve(date + suffix);
    }
}
