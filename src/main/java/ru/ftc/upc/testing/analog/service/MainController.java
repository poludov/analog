package ru.ftc.upc.testing.analog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ftc.upc.testing.analog.model.Line;
import ru.ftc.upc.testing.analog.model.LogChoice;
import ru.ftc.upc.testing.analog.model.Part;
import ru.ftc.upc.testing.analog.model.ReadingMetaData;
import ru.ftc.upc.testing.analog.model.config.ChoiceGroup;
import ru.ftc.upc.testing.analog.model.config.ChoiceProperties;
import ru.ftc.upc.testing.analog.model.config.LogConfigEntry;
import ru.ftc.upc.testing.analog.util.Util;

import javax.servlet.http.HttpSession;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static ru.ftc.upc.testing.analog.service.AnaLogUtils.detectMessageType;
import static ru.ftc.upc.testing.analog.service.AnaLogUtils.distinguishXml;
import static ru.ftc.upc.testing.analog.util.Util.DEFAULT_TITLE_FORMAT;

@RestController
public class MainController {
  private static final Logger log = LoggerFactory.getLogger(MainController.class);

  private final List<ChoiceGroup> choices;
  private final EncodingDetector encodingDetector;

  @Autowired
  public MainController(ChoiceProperties choiceProperties,
                        EncodingDetector encodingDetector) {
    this.choices = choiceProperties.getChoices();
    this.encodingDetector = encodingDetector;
  }

  @RequestMapping("/provide")
  public Part provide(@RequestParam("log") String inputFileName,
                      @RequestParam(required = false, name = "prependingSize") Long prependingSize,
                      @RequestParam(required = false, defaultValue = "UTF-8") String encoding,
                      @RequestParam(required = false, defaultValue = "false") boolean readBackAllowed,
                      HttpSession session) {
    // получаем данные о предыдущем чтении
    ReadingMetaData readingMetaData = AnaLogUtils.retrieveMetaData(session, inputFileName);

    // получаем сырой набор строк из файла
    List<String> rawLines = fetchRawLines(inputFileName, prependingSize, encoding, readingMetaData);
    if (rawLines.isEmpty() && readBackAllowed) {
      log.debug("No new lines fetched. Attempting to read back...");
      readingMetaData.reset();
      rawLines = fetchRawLines(inputFileName, prependingSize, encoding, readingMetaData);
    }

    List<Line> parsedLines = new ArrayList<>();
    for (int i = 0; i < rawLines.size(); i++) {
      // проверяем строку на начало в ней XML-кода
      String curLine = distinguishXml(rawLines, i);

      // вставляем текст строки
      String text = AnaLogUtils.escapeSpecialCharacters(curLine);
      // определяем и вставляем уровень важности сообщения
      String messageType = detectMessageType(curLine);

      // завершаем оформление текущей строки
      parsedLines.add(new Line(text, messageType));
    }

    return new Part(parsedLines);
  }

  private List<String> fetchRawLines(String inputFileName,
                                     Long prependingSize,
                                     String encoding,
                                     ReadingMetaData readingMetaData) {
    List<String> rawLines;
    try {
      rawLines = AnaLogUtils.getRawLines(inputFileName, encoding, readingMetaData, prependingSize);

    } catch (FileNotFoundException e) {
      log.warn("Ошибка при чтении заданного файла: " + e.getMessage());
      throw new RuntimeException(e);

    } catch (Exception e) {
      log.error("Internal application error: ", e);
      throw new RuntimeException(e);
    }
    if (!rawLines.isEmpty()) {
      log.trace("Raw lines read: {}", rawLines.size());
    }
    return rawLines;
  }

  @RequestMapping("/choices")
  public List<LogChoice> choices() {
    return choices.stream()
            .flatMap(this::flattenGroup)
            .collect(toList());
  }

  private Stream<LogChoice> flattenGroup(ChoiceGroup group) {
    Set<LogChoice> choices = new LinkedHashSet<>();
    String groupName = group.getGroup();

    // first let's traverse and process all of the path entries as they are commonly used in groups
    for (LogConfigEntry logConfigEntry : group.getLogs()) {
      String path = logConfigEntry.getPath();
      String titleFormat = Util.nvls(logConfigEntry.getTitle(), DEFAULT_TITLE_FORMAT);
      String title = Util.expandTitle(path, titleFormat, groupName);
      Path rawPath = Paths.get(group.getPathBase(), path);
      Path absPath = rawPath.isAbsolute()
              ? rawPath
              : rawPath.toAbsolutePath();
      String fullPath = absPath.toString();
      String encoding = encodingDetector.getEncodingFor(fullPath);
      choices.add(new LogChoice(groupName,
              fullPath,
              encoding,
              title,
              logConfigEntry.isSelected(),
              logConfigEntry.getUid()));
    }

    // then let's add scanned directory logs to set being composed
    if (group.getScanDir() != null) {
      String groupEncoding = (group.getEncoding() != null)
              ? Util.formatEncodingName(group.getEncoding())
              : null;   // this value will provoke encoding detection
      Path scanDirPath = Paths.get(group.getScanDir());
      try (Stream<Path> scannedPaths = Files.list(scanDirPath)) {
        choices.addAll(scannedPaths   // such sets merging allows to exclude duplicates while preserving explicit paths
                .filter(Files::isRegularFile)   // the scanning is not recursive so we bypass nested directories
                .map(logPath -> new LogChoice(groupName,
                        logPath.toAbsolutePath().toString(),
                        (groupEncoding != null)
                                ? groupEncoding
                                : encodingDetector.getEncodingFor(logPath.toAbsolutePath().toString()),
                        Util.expandTitle(logPath.toString(), DEFAULT_TITLE_FORMAT, groupName),
                        false,
                        null /*TODO decide how to support auto scan feature after v0.7*/))
                .collect(toSet()));
      } catch (IOException e) {
        log.error(format("Failed to scan directory '%s'; will be ignored.", group.getScanDir()), e);
      }
    }

    return choices.stream();
  }
}