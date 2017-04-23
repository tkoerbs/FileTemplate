package com.intershop.filetemplate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 */
public class FileTemplate
{
    public static String fileTemplateSuffix = ".filetemplate";

    //public static int action = -1;  // 0 = LIST, 1 = REPLACE

    //
    // A regular expression matching all {{@PLACEHOLDERNAME@}}
    // We use the reluctant quantifier *? to match shortest possible sequences, e.g. in a line "{{@PH1@}}{{@PH2@}}" match PH1 and PH2 instead of PH1@}}{{@PH2
    // To handle nested placeholder boundaries, e.g. in a line "{{@abc{{@PH1@}}xyz@}}" match PH1 we need to "manually" exclude {{@ from the regex for the PLACEHOLDERNAME
    //
    public static Pattern compiledPlaceholderPatternRegex             = Pattern.compile("\\{\\{@" + "((([^\\{])|(\\{[^\\{])|(\\{\\{[^@]))*?)(\\s*#\\s*base\\s*(\\d+))?" + "@\\}\\}"); // {{@PLACEHOLDERNAME@}}, using reluctant quantifier "*?" (instead of greedy quantifier "*") to match shortest possible sequence
    public static Pattern compiledPlaceholderSectionBeginPatternRegex = Pattern.compile("\\{\\{@" + "((([^\\{])|(\\{[^\\{])|(\\{\\{[^@]))*?)\\s*#\\s*BEGIN\\s*" + "@\\}\\}"); // {{@PLACEHOLDERNAME@}}, using reluctant quantifier "*?" (instead of greedy quantifier "*") to match shortest possible sequence
    public static Pattern compiledPlaceholderSectionEndPatternRegex   = Pattern.compile("\\{\\{@" + "((([^\\{])|(\\{[^\\{])|(\\{\\{[^@]))*?)\\s*#\\s*END\\s*" + "@\\}\\}"); // {{@PLACEHOLDERNAME@}}, using reluctant quantifier "*?" (instead of greedy quantifier "*") to match shortest possible sequence

    public static void printUsage()
    {
        log("std", "usage: FileTemplate action directory [properties-file]");
        log("std", "");
        log("std", "        action           LIST or REPLACE");
        log("std", "                             LIST     Lists all placeholders.");
        log("std", "                             REPLACE  Executes a replacement, for each placeholder you need to supply a value in properties-file.");
        log("std", "");
        log("std", "        directory        The base directory.");
        log("std", "                         May contain files or directories named *.filetemplate");
        log("std", "                         with placeholders in the form \"{{@PLACEHOLDERNAME@}}\" (e.g. {{@SITE@}})");
        log("std", "                         in file/directory names and/or in file content.");
        log("std", "                         All files and directories containing placeholders in their name or content need to be named <real-file-name>.filetemplate, other file's names and content is not touched (but files are copied 1:1 when located in a filetemplate directory).");
        log("std", "");
        log("std", "        properties-file  A Java properties file.");
        log("std", "                         Required in case action = REPLACE.");
        log("std", "                         Contains consecutively numbered placeholder name-value pairs.");
        log("std", "                         The numbering starts with 1, the outermost placeholder.");
        log("std", "                         The name  key is: \"Placeholder#\",");
        log("std", "                         the value key is: \"Placeholder#Value\",");
        log("std", "                         e.g.:");
        log("std", "                             Placeholder1 = SITE");
        log("std", "                             Placeholder1Value = PrimeTech-Site");
        log("std", "                         Types of placeholder values:");
        log("std", "                             Single value");
        log("std", "                             List");
        log("std", "                                 A comma separated list of values.");
        log("std", "                                 Example: Value = [Miller, Jones]");
        log("std", "                                 (In a first copy of the file/dir containing the placeholder, it will be replaced with Miller, in a second copy with Jones.)");
        log("std", "                             Range");
        log("std", "                                 A range of integral numbers, possibly preceeded with leading zeros.");
        log("std", "                                 Example: Value = [001 - 100]");
        log("std", "                                 (100 copies of the file/dir containing the placeholder will be created, using 001, 002, ..., 099 and 100 as placeholders.)");
    }

    /**
     * Diese Methode sucht nach placeholdern
     * @param fileOrDir der parameter muss angegeben werden
     * @param placeholdersWithValues der parameter muss nicht angegeben werden
     * @param fixedPlaceholderValues der parameter muss angegeben werden
     * @param isInCopy der parameter muss nicht angegeben werden
     * @return Es wird zurückgegeben, ob es einen Placeholder gibt oder nicht
     */
    public static Set<String /* placeholder */> processFileOrDir(File fileOrDir, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> fixedPlaceholderValues /* in/out */, boolean isInCopy)
    {
        //
        // Pre-conditions
        //

        if (!fileOrDir.exists())
        {
            log("err", "processFileOrDir: file or directory \"" + fileOrDir.getAbsolutePath() + "\" does not exist");
            return null;
        }

        //
        // Main tasks
        //

        Set<String /* placeholder */> foundPlaceholders = new HashSet<String /* placeholder */>();

        String fileName = fileOrDir.getName();
        boolean fileNameEndsWithTemplateSuffix = fileName.endsWith(fileTemplateSuffix);

        if (fileOrDir.isDirectory() && (!fileNameEndsWithTemplateSuffix || placeholdersWithValues == null)) // a dir that is not a template or a dir in LIST mode
        {
            if (placeholdersWithValues == null)
            {
                // action == LIST
                foundPlaceholders = getPlaceholdersFromString(fileName);
            }

            foundPlaceholders.addAll(processDirContent(fileOrDir, placeholdersWithValues, fixedPlaceholderValues /* in */, isInCopy));
        }
        else if (fileNameEndsWithTemplateSuffix)
        {
            fileName = fileName.substring(0, fileName.length() - fileTemplateSuffix.length());

            if (placeholdersWithValues == null)
            {
                // action == LIST
                foundPlaceholders = getPlaceholdersFromString(fileName);

                if (!fileOrDir.isDirectory()) {
                    foundPlaceholders.addAll(getPlaceholdersFromFileContent(fileOrDir));
                }
            }
            else
            {
                // action == REPLACE

                // shallow copy fixedPlaceholderValues into currentPlaceholderValues
                Map<String /* placeholder */, String /* placeholder value */> currentPlaceholderValues = new HashMap<String /* placeholder */, String /* placeholder value */>(fixedPlaceholderValues);

                boolean isFirstCall = true;
                boolean isCopied = false;
                String newFileName = null;
                do
                {
                    newFileName = getNextFileNameFromFileTemplateName(fileName, placeholdersWithValues, fixedPlaceholderValues /* in */, currentPlaceholderValues /* in/out */, isFirstCall);
                    isFirstCall = false;
                    if (newFileName != null)
                    {
                        if (fileOrDir.isDirectory())
                        {
                            replaceDirWithCopy(fileOrDir, newFileName != null ? newFileName : fileName, placeholdersWithValues, currentPlaceholderValues);
                        }
                        else // fileOrDir is not a directory
                        {
                            replaceFileWithCopy(fileOrDir, newFileName != null ? newFileName : fileName, placeholdersWithValues, currentPlaceholderValues);
                        }

                        isCopied = true;
                    }
                }
                while (newFileName != null);

                if (isInCopy && isCopied)
                {
                    deepDelete(fileOrDir);
                }
            }
        }

        return foundPlaceholders;
    }

    /**
     * Prüft ob der placeholder verwendbar ist
     * @param dir der parameter muss angegeben werden
     * @param placeholdersWithValues der parameter muss angegeben werden
     * @param fixedPlaceholderValues der parameter muss angegeben werden
     * @param isInCopy der parameter muss nicht angegeben werden
     * @return Es wird zurückgegeben, ob es einen Placeholder gibt oder nicht
     */
    public static Set<String /* placeholder */> processDirContent(File dir, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> fixedPlaceholderValues /* in */, boolean isInCopy)
    {
        //
        // Pre-conditions
        //

        if (!dir.exists())
        {
            log("err", "processDir: file \"" + dir.getAbsolutePath() + "\" does not exist");
            return null;
        }

        if (!dir.isDirectory())
        {
            log("err", "processDir: file \"" + dir.getAbsolutePath() + "\" exists, but is not a directory (probably a file)");
            return null;
        }

        //
        // Main tasks
        //

        Set<String /* placeholder */> foundPlaceholders = new HashSet<String /* placeholder */>();

        for (File dirElement : Arrays.asList(dir.listFiles()))
        {
            foundPlaceholders.addAll(processFileOrDir(dirElement, placeholdersWithValues, fixedPlaceholderValues /* in/out */, isInCopy));
        }

        return foundPlaceholders;
    }

    /**
     * Sucht erneut nach placeholdern
     * @param dir der parameter muss angegeben werden
     * @param newDirName der parameter muss angegeben werden
     * @param placeholdersWithValues der parameter muss angegeben werden
     * @param currentPlaceholderValues der parameter muss angegeben werden
     * @return Es wird zurückgegeben, ob es einen Placeholder gibt oder nicht
     */
    public static Set<String /* placeholder */> replaceDirWithCopy(File dir, String newDirName, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> currentPlaceholderValues)
    {
        //
        // Pre-conditions
        //

        if (!dir.exists())
        {
            log("err", "replaceDirWithCopy: file \"" + dir.getAbsolutePath() + "\" does not exist");
            return null;
        }

        if (!dir.isDirectory())
        {
            log("err", "replaceDirWithCopy: file \"" + dir.getAbsolutePath() + "\" exists, but is not a directory (probably a file)");
            return null;
        }

        //
        // Main tasks
        //

        Set<String /* placeholder */> foundPlaceholders = Collections.emptySet();

        File parentDir = dir.getParentFile();
        File newDir = new File(parentDir.getAbsolutePath() + File.separatorChar + newDirName);
        if (newDir.exists())
        {
            log("std", "WARNING: replaceDirWithCopy: directory \"" + newDir.getAbsolutePath() + "\" already exists, removing it first");
            deepDelete(newDir);
        }

        foundPlaceholders = replaceDirContent(dir, newDir, placeholdersWithValues, currentPlaceholderValues);

        return foundPlaceholders;
    }

    /**
     * Kopiert Dir nach newDir und dann ersetzt er in newDir die Placeholder
     * @param dir der parameter muss nicht angegeben werden
     * @param newDir der parameter muss nicht angegeben werden
     * @param placeholdersWithValues der parameter muss angegeben werden
     * @param currentPlaceholderValues der parameter muss angegeben werden
     * @return Entwieder gibt er nichts aus bzw. eine Fehlermeldung, wenn alles vorhanden ist greift er auf processDirContent zu
     */
    public static Set<String /* placeholder */> replaceDirContent(File dir, File newDir, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> currentPlaceholderValues)
    {
        //
        // Pre-conditions
        //

        if (!dir.exists())
        {
            log("err", "replaceDirContent: dir \"" + dir.getAbsolutePath() + "\" does not exist");
            return null;
        }

        if (!dir.isDirectory())
        {
            log("err", "replaceDirContent: dir \"" + dir.getAbsolutePath() + "\" exists, but is not a file (probably a file)");
            return null;
        }

        if (newDir.exists())
        {
            log("err", "replaceDirContent: dir \"" + newDir.getAbsolutePath() + "\" already exists");
            return null;
        }

        //
        // Main tasks
        //

        // Deep copy dir --> newDir,
        // then process newDir using processDirContent(...) with isInCopy = true

        deepCopy(dir, newDir);
        return processDirContent(newDir, placeholdersWithValues, currentPlaceholderValues, true);
    }

    /**
     * Legt datei mit dem namen "New file newFileName" an
     * Wenn "newFileName" schon exestiert wird es gelöscht  
     * dann kopiert er Inhalt von "file" nach "newFileName" und ersetzt Placeholder
     * @param file der parameter muss angegeben werden
     * @param newFileName der parameter muss angegeben werden
     * @param placeholdersWithValues der parameter muss angegeben werden
     * @param currentPlaceholderValues der parameter muss angegeben werden
     * @return Es wird zurückgegeben, ob es einen Placeholder gibt oder nicht
     */
    public static Set<String /* placeholder */> replaceFileWithCopy(File file, String newFileName, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> currentPlaceholderValues)
    {
        //
        // Pre-conditions
        //

        if (!file.exists())
        {
            log("err", "replaceFileWithCopy: file \"" + file.getAbsolutePath() + "\" does not exist");
            return null;
        }

        if (!file.isFile())
        {
            log("err", "replaceFileWithCopy: file \"" + file.getAbsolutePath() + "\" exists, but is not a file (probably a directory)");
            return null;
        }

        //
        // Main tasks
        //

        Set<String /* placeholder */> foundPlaceholders = Collections.emptySet();

        File parentDir = file.getParentFile();
        File newFile = new File(parentDir.getAbsolutePath() + File.separatorChar + newFileName);
        if (newFile.exists())
        {
            log("std", "WARNING: replaceFileWithCopy: file \"" + newFile.getAbsolutePath() + "\" already exists, removing it first");
            deepDelete(newFile);
        }

        foundPlaceholders = replaceFileContent(file, newFile, placeholdersWithValues, currentPlaceholderValues);

        return foundPlaceholders;
    }

    /**
     * kopiert Inhalt von "file" nach "newFileName" und ersetzt Placeholder
     * @param file der parameter muss angegeben werden
     * @param newFile der parameter muss angegeben werden, das file darf aber nicht exestieren
     * @param placeholdersWithValues der parameter muss angegeben werden
     * @param currentPlaceholderValues der parameter muss nicht angegeben werden
     * @return 
     */
    public static Set<String /* placeholder */> replaceFileContent(File file, File newFile, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> currentPlaceholderValues)
    {
        //
        // Pre-conditions
        //

        if (!file.exists())
        {
            log("err", "replaceFileContent: file \"" + file.getAbsolutePath() + "\" does not exist");
            return null;
        }

        if (!file.isFile())
        {
            log("err", "replaceFileContent: file \"" + file.getAbsolutePath() + "\" exists, but is not a file (probably a directory)");
            return null;
        }

        if (newFile.exists())
        {
            log("err", "replaceFileContent: file \"" + newFile.getAbsolutePath() + "\" already exists");
            return null;
        }

        //
        // Main tasks
        //

        Set<String /* placeholder */> foundPlaceholders = new HashSet<String /* placeholder */>();
        
        BufferedReader inputFile = null;

        try
        {
            //
            // Fill stack of LinesWithPlaceholderBlock's with all lines of file.
            //
            
            LinesWithPlaceholderBlock startPlaceholderBlock = new LinesWithPlaceholderBlock(null);
            LinesWithPlaceholderBlock currentPlaceholderBlock = startPlaceholderBlock;

            inputFile = new BufferedReader(new FileReader(file));
            String line;
            while ((line = inputFile.readLine()) != null)
            {
                String placeholder;
                if ((placeholder = isEndPlaceholderLine(line)) != null)  // case: {{@PLACEHOLDERNAME# END @}}
                {
                    currentPlaceholderBlock = currentPlaceholderBlock.parent;
                    if (currentPlaceholderBlock == null)
                    {
                        log("err", "replaceFileContent: count of END of placeholder sections larger than count of BEGIN (at END of placeholder \"" + placeholder + "\")");
                        return foundPlaceholders;
                    }
                    else if (currentPlaceholderBlock.placeholder == null)
                    {
                        log("err", "replaceFileContent: no BEGIN for END of placeholder \"" + placeholder + "\"");
                        return foundPlaceholders;
                    }
                    else if (!placeholder.equals(currentPlaceholderBlock.placeholder))
                    {
                        log("err", "replaceFileContent: found END of placeholder \"" + placeholder + "\" where expecting END of placeholder \"" + currentPlaceholderBlock.placeholder + "\"");
                        return foundPlaceholders;
                    }
                }
                else
                {
                    if (currentPlaceholderBlock.placeholder != null)
                    {
                        // chain new LinesWithPlaceholderBlock at same level
                        currentPlaceholderBlock = currentPlaceholderBlock.nextLinesWithPlaceholderBlock = new LinesWithPlaceholderBlock(currentPlaceholderBlock.parent);
                    }

                    if ((placeholder = isBeginPlaceholderLine(line)) != null)  // case: {{@PLACEHOLDERNAME# BEGIN @}}
                    {
                        currentPlaceholderBlock.placeholder = placeholder;
                        currentPlaceholderBlock = new LinesWithPlaceholderBlock(currentPlaceholderBlock);
                        currentPlaceholderBlock.parent.linesOfPlaceholderBlock = currentPlaceholderBlock;
                        foundPlaceholders.add(placeholder);
                    }
                    else
                    {
                        currentPlaceholderBlock.linesBeforePlaceholder.add(line);  // case: regular line
                    }
                }
            }
            
            inputFile.close();
            inputFile = null;
            
            if (currentPlaceholderBlock.parent != null)
            {
                log("err", "replaceFileContent: missing END for placeholder \"" + currentPlaceholderBlock.parent.placeholder + "\"");
                return foundPlaceholders;
            }
            
            //
            // Process stack of LinesWithPlaceholderBlock's: Replace placeholders and write to output file.
            //
            
            BufferedWriter outputFile = new BufferedWriter(new FileWriter(newFile));

            processLinesWithPlaceholderBlock(startPlaceholderBlock, outputFile, placeholdersWithValues, currentPlaceholderValues);
            
            outputFile.close();
        }
        catch(IOException exIO)
        {
            log("err", "replaceFileContent: \"" + file.getAbsolutePath() + "\" --> \"" + newFile.getAbsolutePath() + "\": " + exIO.toString());
        }
        finally
        {
            if (inputFile != null)
            {
                try
                {
                    inputFile.close();
                }
                catch (IOException exIO)
                {
                    log("err", "replaceFileContent: unable to close \"" + file.getAbsolutePath() + "\": " + exIO.toString());
                }
            }
        }

        return foundPlaceholders;
    }

    /**
     * Ersetzt einen in einem PlaceholderBlock alle Placeholder
     * @param currentPlaceholderBlock der parameter muss angegeben werden
     * @param outputFile der parameter muss angegeben werden
     * @param placeholdersWithValues der parameter muss angegeben werden
     * @param currentPlaceholderValues der parameter muss angegeben werden
     * @throws IOException der werfer muss angegeben werden
     */ 
    public static void processLinesWithPlaceholderBlock(LinesWithPlaceholderBlock currentPlaceholderBlock, BufferedWriter outputFile, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> currentPlaceholderValues) throws IOException
    {
        do
        {
            for (String line : currentPlaceholderBlock.linesBeforePlaceholder)
            {
                String replacedLine = replaceAllOccurrencesInString(line, placeholdersWithValues, currentPlaceholderValues);
                outputFile.write(replacedLine);
                outputFile.newLine();
            }
            
            if (currentPlaceholderBlock.placeholder != null)
            {
                Map<String /* placeholder */, String /* placeholder value */> fixedPlaceholderValuesInBlock = new HashMap<String /* placeholder */, String /* placeholder value */>(currentPlaceholderValues);
                PlaceholderDefinition placeholderValue = placeholdersWithValues.get(currentPlaceholderBlock.placeholder);

                if (placeholderValue != null)
                {
                    for (String currentPlaceholderValueInBlock = placeholderValue.getNextPlaceholderValue(null); currentPlaceholderValueInBlock != null; currentPlaceholderValueInBlock = placeholderValue.getNextPlaceholderValue(currentPlaceholderValueInBlock))
                    {
                        fixedPlaceholderValuesInBlock.put(currentPlaceholderBlock.placeholder, currentPlaceholderValueInBlock);
                        processLinesWithPlaceholderBlock(currentPlaceholderBlock.linesOfPlaceholderBlock, outputFile, placeholdersWithValues, fixedPlaceholderValuesInBlock);
                    }
                }
                else
                {
                    log("err", "processLinesWithPlaceholderBlock: no definition for used placeholder \"" + currentPlaceholderBlock.placeholder + "\"");
                }
            }
            
            currentPlaceholderBlock = currentPlaceholderBlock.nextLinesWithPlaceholderBlock;  // next LinesWithPlaceholderBlock at same level
        }  
        while (currentPlaceholderBlock != null);
    }  

    /**
     * Liest eine Datei ein und sucht in ihr nach Placeholdern
     * @param file der parameter muss angegeben werden
     * @return Es werden alle Placeholder zurückgegeben
     */
    public static Set<String /* placeholder */> getPlaceholdersFromFileContent(File file)
    {
        //
        // Pre-conditions
        //

        if (!file.exists())
        {
            log("err", "replaceFileContent: file \"" + file.getAbsolutePath() + "\" does not exist");
            return null;
        }

        if (!file.isFile())
        {
            log("err", "replaceFileContent: file \"" + file.getAbsolutePath() + "\" exists, but is not a file (probably a directory)");
            return null;
        }

        //
        // Main tasks
        //

        Set<String /* placeholder */> foundPlaceholders = new HashSet<String /* placeholder */>();

        try
        {
            BufferedReader inputFile = new BufferedReader(new FileReader(file));
            String line;
            while ((line = inputFile.readLine()) != null)
            {
                String nextLine;
                while (line.endsWith("\\") && (nextLine = inputFile.readLine()) != null)
                {
                    line = line + System.getProperty("line.separator") + nextLine;
                }

                foundPlaceholders.addAll(getPlaceholdersFromString(line));
            }

            inputFile.close();
        }
        catch(IOException exIO)
        {
            log("err", "getPlaceholdersFromFileContent: \"" + file.getAbsolutePath() + "\": " + exIO.toString());
        }

        return foundPlaceholders;
    }

    
    /**
     * Sucht in string nach Placeholdern
     * @param string der parameter muss angegeben werden
     * @return geht auf placeholder zurück
     */
    public static Set<String /* placeholder */> getPlaceholdersFromString(String string)
    {
        // grep all <@PLACEHOLDERNAME@> from fileName using regex "<@(.*?)@>"
        Set<String /* placeholder */> placeholders = new HashSet<String /* placeholder */>();
        Matcher placeholderPatternMatcher = compiledPlaceholderPatternRegex.matcher(string);
        while (placeholderPatternMatcher.find())
        {
            String placeholder = placeholderPatternMatcher.group(1);
            placeholders.add(placeholder);
        }

        return placeholders;
    }

    /**
     * 
     * @param fileName der parameter muss angegeben werden
     * @param placeholdersWithValues der parameter muss angegeben werden
     * @param fixedPlaceholderValues der parameter muss angegeben werden
     * @param currentPlaceholderValues der parameter muss angegeben werden
     * @param isFirstCall der parameter muss angegeben werden
     * @return
     */
    public static String getNextFileNameFromFileTemplateName(String fileName, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> fixedPlaceholderValues /* in */, Map<String /* placeholder */, String /* placeholder value */> currentPlaceholderValues /* in/out */, boolean isFirstCall)
    {
        boolean isNewPlaceholderSelected = false;

        String cyclePlaceholder = null;

        String newFileName = fileName;

        Iterator<String> placeholderNames = placeholdersWithValues.keySet().iterator();
        while (placeholderNames.hasNext())
        {
            String placeholderName = placeholderNames.next();
            PlaceholderDefinition placeholderValue = placeholdersWithValues.get(placeholderName);
            String currentPlaceholderValue = null;

            StringBuilder newFileNameBuilder = new StringBuilder(newFileName.length() + 30);
            int uncopiedSectionStartIndex = 0;
            Pattern compiledQualifiedPlaceholderPatternRegex = Pattern.compile("\\{\\{@" + "\\Q" + placeholderName + "\\E" + "(\\s*#\\s*base\\s*(\\d+))?" + "@\\}\\}"); // {{@placeholderName[#base 001]@}}
            Matcher qualifiedPlaceholderPatternMatcher = compiledQualifiedPlaceholderPatternRegex.matcher(newFileName);
            if (qualifiedPlaceholderPatternMatcher.find())
            {
                currentPlaceholderValue = fixedPlaceholderValues.get(placeholderName);

                if (currentPlaceholderValue == null) {
                    // We need to use the next of the supplied values for this placeholder.
                    currentPlaceholderValue = currentPlaceholderValues.get(placeholderName);
                    if (placeholderValue.isSinglePlaceholderValue()) {
                        if (currentPlaceholderValue == null) {
                            currentPlaceholderValue = placeholderValue.getNextPlaceholderValue(currentPlaceholderValue);
                            isNewPlaceholderSelected = true;
                        }
                    }
                    else // multi-value placeholder
                    {
                        if (cyclePlaceholder == null) {
                            cyclePlaceholder = placeholderName;
                        }

                        if (cyclePlaceholder.equals(placeholderName)) {
                            currentPlaceholderValue = placeholderValue.getNextPlaceholderValue(currentPlaceholderValue);
                            if (currentPlaceholderValue == null)
                            {
                                // get first placeholder value
                                currentPlaceholderValue = placeholderValue.getNextPlaceholderValue(currentPlaceholderValue);
                                // another (next outer) placeholder may cycle now
                                // or whole replacement loop may end
                                cyclePlaceholder = null;
                            }
                            else
                            {
                                isNewPlaceholderSelected = true;
                            }
                        }
                        else
                        {
                            if (currentPlaceholderValue == null) {
                                currentPlaceholderValue = placeholderValue.getNextPlaceholderValue(currentPlaceholderValue);
                                isNewPlaceholderSelected = true;
                            }
                        }
                    }

                    currentPlaceholderValues.put(placeholderName, currentPlaceholderValue);
                }

                do
                {
                    newFileNameBuilder.append(newFileName.substring(uncopiedSectionStartIndex, qualifiedPlaceholderPatternMatcher.start()));

                    String baseNumber = qualifiedPlaceholderPatternMatcher.group(2);
                    if (baseNumber != null)
                    {
                        //log("std", "getGetNextFileNameFromFileTemplateName: placeholder \"" + placeholderName + "\" to be replaced with number of value instead of value itself, base number: " + baseNumber);
                        currentPlaceholderValue = placeholderValue.getNumberOfPlaceholderValue(currentPlaceholderValue, baseNumber);
                    }

                    newFileNameBuilder.append(currentPlaceholderValue);

                    uncopiedSectionStartIndex = qualifiedPlaceholderPatternMatcher.end();
                }
                while (qualifiedPlaceholderPatternMatcher.find());
            }

            newFileNameBuilder.append(newFileName.substring(uncopiedSectionStartIndex));

            newFileName = newFileNameBuilder.toString();
        }

        if (isNewPlaceholderSelected || isFirstCall)
        {
            log("std", "getGetNextFileNameFromFileTemplateName: \"" + fileName + fileTemplateSuffix + "\" --> \"" + newFileName + "\"");
        }

        return isNewPlaceholderSelected || isFirstCall ? newFileName : null; 
    }
    
    /**
     * @param line der parameter muss angegeben werden
     * @return gibt placeholder an
     */
    public static String isBeginPlaceholderLine(String line)
    {
        String placeholder= null;
        
        Matcher placeholderSectionBeginPatternMatcher = compiledPlaceholderSectionBeginPatternRegex.matcher(line);
        if (placeholderSectionBeginPatternMatcher.find())
        {
            placeholder = placeholderSectionBeginPatternMatcher.group(1);
            
            int startIndex = placeholderSectionBeginPatternMatcher.start();
            int endIndex = placeholderSectionBeginPatternMatcher.end();
            if (line.substring(0, startIndex).trim().length() > 0
                            || line.substring(endIndex).trim().length() > 0)
            {
                log("err", "isBeginPlaceholderLine: in the following line there is more than just the BEGIN of placeholder \"" + placeholder + "\", and everything than the placeholder BEGIN is ignored: " + line);
            }
        }
        
        return placeholder;
    }

    /**
     * @param line der parameter muss angegeben werden
     * @return gibt placeholder an
     */
    public static String isEndPlaceholderLine(String line)
    {
        String placeholder= null;
        
        Matcher placeholderSectionEndPatternMatcher = compiledPlaceholderSectionEndPatternRegex.matcher(line);
        if (placeholderSectionEndPatternMatcher.find())
        {
            placeholder = placeholderSectionEndPatternMatcher.group(1);
            
            int startIndex = placeholderSectionEndPatternMatcher.start();
            int endIndex = placeholderSectionEndPatternMatcher.end();
            if (line.substring(0, startIndex).trim().length() > 0
                            || line.substring(endIndex).trim().length() > 0)
            {
                log("err", "isBeginPlaceholderLine: in the following line there is more than just the END of placeholder \"" + placeholder + "\", and everything than the placeholder END is ignored: " + line);
            }
        }
        
        return placeholder;
    }

    /**
     * Ersetzt Placeholder in "line"
     * @param line der parameter muss angegeben werden
     * @param placeholdersWithValues der parameter muss angegeben werden
     * @param currentPlaceholderValues der parameter muss angegeben werden
     * @return Kopiert line und ersetzt Placeholder 
     */
    public static String /* replacedLine */ replaceAllOccurrencesInString(String line, Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues, Map<String /* placeholder */, String /* placeholder value */> currentPlaceholderValues)
    {
        StringBuilder replacedLineBuilder = new StringBuilder(line.length() + 30);

        // grep all <@PLACEHOLDERNAME@> from fileName using regex "<@(.*?)@>"
        int uncopiedSectionStartIndex = 0;
        Matcher placeholderPatternMatcher = compiledPlaceholderPatternRegex.matcher(line);
        while (placeholderPatternMatcher.find())
        {
            //String placeholder = placeholderPatternMatcher.group(1);
            //placeholders.add(placeholder);
            replacedLineBuilder.append(line.substring(uncopiedSectionStartIndex, placeholderPatternMatcher.start()));

            String placeholder = placeholderPatternMatcher.group(1);

            String replacementValue = null;
            if (currentPlaceholderValues.containsKey(placeholder))
            {
                replacementValue = currentPlaceholderValues.get(placeholder);
            }
            else
            {
                PlaceholderDefinition placeholderDefinition = placeholdersWithValues.get(placeholder);
                if (placeholderDefinition != null)
                {
                    if (placeholderDefinition.isSinglePlaceholderValue())
                    {
                        replacementValue = placeholderDefinition.getNextPlaceholderValue(null);
                    }
                    else // multi-value placeholder
                    {
                        log("err", "replaceAllOccurrencesInStrings: placeholder \"" + placeholder + "\" encountered outside BEGIN/END section without fixed value, ignoring it");
                    }
                }
                else
                {
                    log("err", "replaceAllOccurrencesInStrings: placeholder \"" + placeholder + "\" is not defined in properties file, ignoring it");
                }
            }

            String baseNumber = placeholderPatternMatcher.group(7);
            if (baseNumber != null)
            {
                PlaceholderDefinition placeholderDefinition = placeholdersWithValues.get(placeholder);
                replacementValue = placeholderDefinition.getNumberOfPlaceholderValue(replacementValue, baseNumber);
                //log("std", "replaceAllOccurrencesInStrings: placeholder \"" + placeholder + "\" to be replaced with number of value instead of value itself, base number: " + baseNumber);
            }

            replacedLineBuilder.append(replacementValue);

            uncopiedSectionStartIndex = placeholderPatternMatcher.end();
        }

        replacedLineBuilder.append(line.substring(uncopiedSectionStartIndex));

        return replacedLineBuilder.toString();
    }

    /**
     * @param args der parameter muss angegeben werden
     * @throws InterruptedException
     */
    public static void main(String args[]) throws InterruptedException
    {
        if (args.length < 2)
        {
            printUsage();
            return;
        }

        Map<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues = null;

        if (args[0].equals("LIST"))
        {
            // no op, placeholdersWithValues is set to null
        }
        else if (args[0].equals("REPLACE"))
        {
            if (args.length < 3)
            {
                log("err", "REPLACE requires a properties-file");
                return;
            }

            File propertiesFile = new File(args[2]);
            Properties properties = new Properties();
            try
            {
                properties.load(new FileInputStream(propertiesFile));
            }
            catch(FileNotFoundException e)
            {
                log("err", "properties-file \"" + args[2] + "\" not found");
                return;
            }
            catch(IOException e)
            {
                log("err", "unable to read properties-file \"" + args[2] + "\"");
                return;
            }

            placeholdersWithValues = readPlaceholdersFromProperties(properties);
        }
        else
        {
            log("err", "action \"" + args[0] + "\" not supported");
            log("std", "");
            printUsage();
            return;
        }

        File baseDir = new File(args[1]);
        if (!baseDir.exists())
        {
            log("err", "directory \"" + args[1] + "\" does not exist");
            return;
        }

        Set<String /* placeholder */> foundPlaceholders = processFileOrDir(baseDir, placeholdersWithValues, new HashMap<String /* placeholder */, String /* placeholder value */>(), false);
        if (placeholdersWithValues == null)
        {
            log("std", "Listing all placeholders found:");
            for (String placeholder : foundPlaceholders)
            {
                log("std", placeholder);
            }
        }
    }

    /**
     * Kopiert gesamten Inhalt von sourcheLocation nach targeLocaion.
     * If targetLocation does not exist, it will be created.
     * @param sourceLocation der parameter muss angegeben werden
     * @param targetLocation der parameter muss angegeben werden
     */
    public static void deepCopy(File sourceLocation , File targetLocation)
    {
        if (sourceLocation.isDirectory())
        {
            if (!targetLocation.exists())
            {
                targetLocation.mkdir();
            }

            String[] children = sourceLocation.list();
            for (int i=0; i<children.length; i++)
            {
                deepCopy(new File(sourceLocation, children[i]), new File(targetLocation, children[i]));
            }
        }
        else
        {
            try
            {
                InputStream in = new FileInputStream(sourceLocation);
                OutputStream out = new FileOutputStream(targetLocation);

                // Copy the bits from instream to outstream
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0)
                {
                    out.write(buf, 0, len);
                }
                in.close();
                out.close();
            }
            catch(IOException exIO)
            {
                log("err", "deepCopy: \"" + sourceLocation + "\" --> \"" + targetLocation + "\": " + exIO.toString());
            }
        }
    }

    /**
     * Ist zum Löschen einer bestimmten datei angelegt
     * 
     * @param location  Darf nicht leer sein.
     * @return true wenn alles gelöscht wurde, 
     */
    public static boolean deepDelete(File location)
    {
        boolean success = true;

        if (location.isDirectory())
        {
            String[] children = location.list();
            for (int i=0; i<children.length; i++)
            {
                success = deepDelete(new File(location, children[i])) && success;
            }

            if (success && !location.delete())
            {
                log("err", "deepDelete: file \"" + location.getAbsolutePath() + "\" could not be deleted");
                success = false;
            }
        }
        else
        {
            if (!location.delete())
            {
                log("err", "deepDelete: file \"" + location.getAbsolutePath() + "\" could not be deleted");
                success = false;
            }
        }

        return success;
    }

    /**
     * @param properties der parameter muss angegeben werden
     * @return 
     */
    public static LinkedHashMap<String /* placeholder */, PlaceholderDefinition> readPlaceholdersFromProperties(Properties properties)
    {
        LinkedHashMap<String /* placeholder */, PlaceholderDefinition> placeholdersWithValues = new LinkedHashMap<String /* placeholder */, PlaceholderDefinition>();
        List<String> placeholderNames = new LinkedList<String>();

        String placeholderName = "0";

        for (int currentPlaceholderNumber = 1; placeholderName != null; currentPlaceholderNumber++)
        {
            String nameKey = "Placeholder" + currentPlaceholderNumber;
            placeholderName = properties.getProperty(nameKey);
            if (placeholderName != null)
            {
                String valueKey = nameKey + "Value";
                String placeholderValue = properties.getProperty(valueKey);
                if (placeholderValue != null)
                {
                    PlaceholderDefinition placeholderDefinition = new PlaceholderDefinition(placeholderName, placeholderValue);
                    placeholdersWithValues.put(placeholderName, placeholderDefinition);
                    placeholderNames.add(placeholderName);
                }
                else
                {
                    log("err", "no " + nameKey + "Value found for " + nameKey);
                }
            }
        }

        LinkedHashMap<String /* placeholder */, PlaceholderDefinition> placeholdersWithValuesReversed = new LinkedHashMap<String /* placeholder */, PlaceholderDefinition>();
        Collections.reverse(placeholderNames);
        Iterator<String> reversePlaceholderNamesIterator = placeholderNames.iterator();
        while (reversePlaceholderNamesIterator.hasNext())
        {
            placeholderName = reversePlaceholderNamesIterator.next();
            PlaceholderDefinition placeholderDefinition = placeholdersWithValues.get(placeholderName);
            placeholdersWithValuesReversed.put(placeholderName, placeholderDefinition);
        }

        return placeholdersWithValuesReversed;
    }

    /**
     * Gibt Meldungen aus
     * @param logScope der parameter muss nicht angegeben werden
     * @param msg der parameter muss nicht angegeben werden
     */
    public static void log(String logScope, String msg)
    {
        String dateString = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());

        if ("err".equals(logScope))
        {
            System.err.println(dateString + ' ' + msg);
        }
        else if ("none".equals(logScope))
        {
        }
        else if ("std".equals(logScope))
        {
            System.out.println(dateString + ' ' + msg);
        }
        else
        {
            System.err.println(dateString + ' ' + "no valid log scope (" + logScope + ") for message: " + msg);
        }
    }
}

class LinesWithPlaceholderBlock
{
    public LinesWithPlaceholderBlock parent = null;
    public LinesWithPlaceholderBlock(LinesWithPlaceholderBlock parent)
    {
        this.parent = parent;
    }

    public String placeholder = null;
    public ArrayList<String> linesBeforePlaceholder = new ArrayList<String>();
    public LinesWithPlaceholderBlock linesOfPlaceholderBlock = null;
    public LinesWithPlaceholderBlock nextLinesWithPlaceholderBlock = null;  // next LinesWithPlaceholderBlock at same level
}

class PlaceholderDefinition
{
    String name;
    boolean singlePlaceholderValue = false;
    boolean rangePlaceholderValue = false;
    boolean listPlaceholderValue = false;
    String valueSingle;
    String valueRangeBegin;
    String valueRangeEnd;
    int intValueRangeBegin;
    int intValueRangeEnd;
    boolean rangeInverted = false;  // true if intValueRangeBegin > intValueRangeEnd
    List<String> valueList;

    /**
     * Analysiert den wert des placeholders aus der "properties" datei
     * 
     * @param name der parameter muss angegeben werden
     * @param value der parameter muss angegeben werden
     */
    public PlaceholderDefinition(String name, String value)
    {
        this.name = name;

        if (value.trim().startsWith("[") && value.trim().endsWith("]"))
        {
            // if Placeholder1StartValue starts with [ and ends with ] then
            // it is either a comma separated list of values, e.g. [abc, def, xyz]
            // or a range with with two dash separated integral values, e.g. [00116 - 04000]
            value = value.trim().substring(1, value.length() - 1); // strip leading and trailing [ and ]
            Matcher matcher = Pattern.compile("^\\s*(\\d+)\\s*\\-\\s*(\\d+)\\s*$").matcher(value);
            if (matcher.find())
            {
                rangePlaceholderValue = true;
                valueRangeBegin = matcher.group(1);
                valueRangeEnd = matcher.group(2);
                intValueRangeBegin = Integer.parseInt(valueRangeBegin);
                intValueRangeEnd = Integer.parseInt(valueRangeEnd);
                if (intValueRangeBegin <= intValueRangeEnd)
                {
                    FileTemplate.log("std", "PlaceholderDefinition: placeholder " + name + ": range from  " + valueRangeBegin + " to " + valueRangeEnd);
                }
                else
                {
                    rangeInverted = true;
                    FileTemplate.log("std", "PlaceholderDefinition: placeholder " + name + ": inverted range from " + valueRangeBegin + " to " + valueRangeEnd);
                }
            }
            else if (value.matches(".*\\S+.*,.*\\S+.*"))
            {
                listPlaceholderValue = true;
                valueList = Collections.synchronizedList(Arrays.asList(value.split("\\s*,\\s*")));
                FileTemplate.log("std", "PlaceholderDefinition: placeholder " + name + ": list of " + valueList.size());
            }
        }
        else
        {
            singlePlaceholderValue = true;
            valueSingle = value;
            FileTemplate.log("std", "PlaceholderDefinition: placeholder " + name + ": single value: " + valueSingle);
        }
    }

    public String getName()
    {
        return name;
    }

    /**
     * Gibt den nächsten Wert aus
     * @param valuePlaceholder der parameter muss nicht angegeben werden
     * @return null if no more placeholders
     */
    public String getNextPlaceholderValue(String valuePlaceholder)
    {
        if (listPlaceholderValue)
        {
            if (valuePlaceholder == null)
            {
                return valueList.get(0);
            }
            else
            {
                Iterator<String> iterValueList = valueList.iterator();
                while (iterValueList.hasNext() && !iterValueList.next().equals(valuePlaceholder));
                if (iterValueList.hasNext())
                {
                    return iterValueList.next();
                }
                else
                {
                    return null; // end of sequence reached
                }
            }
        }
        else if (rangePlaceholderValue)
        {
            if (valuePlaceholder == null)
            {
                return valueRangeBegin;
            }
            else
            {
                // Only if no placeholders iterator is available next placeholder is
                // calculated.
                int len = valuePlaceholder.length();

                // Remove leading zeros.
                // while (valuePlaceholder.startsWith("0")) valuePlaceholder =
                // valuePlaceholder.substring(1);
                // if (valuePlaceholder.length() == 0) valuePlaceholder = "0";

                // Convert to int.
                int intValue = Integer.parseInt(valuePlaceholder);

                intValue = intValue + (rangeInverted ? -1 : 1);

                if (intValue < intValueRangeBegin || intValue > intValueRangeEnd)
                {
                    return null; // end of sequence reached
                }

                // Add 1 and convert back.
                valuePlaceholder = Integer.toString(intValue);

                // Pad with zeros if necessary.
                while(valuePlaceholder.length() < len)
                {
                    valuePlaceholder = "0" + valuePlaceholder;
                }

                return valuePlaceholder;
            }
        }
        else // singlePlaceholderValue
        {
            return valuePlaceholder == null ? valueSingle : null;
        }
    }

    /**
     * @param valuePlaceholder der parameter muss nicht angegeben werden
     * @param baseNumber der parameter muss angegeben werden
     * @return gibt die Anzahl der Placeholderwiederholungen an
     */
    public String getNumberOfPlaceholderValue(String valuePlaceholder, String baseNumber)
    {
        if (listPlaceholderValue)
        {
            if (valuePlaceholder == null)
            {
                FileTemplate.log("std", "getNumberOfPlaceholderValue(\"" + valuePlaceholder + "\", \"" + baseNumber + "\") called for list value " + name);
                return baseNumber;
            }
            else
            {
                Iterator<String> iterValueList = valueList.iterator();
                int numberOfPlaceholderValue = 0;
                String iterValue = null;
                while (iterValueList.hasNext() && !(iterValue = iterValueList.next()).equals(valuePlaceholder))
                {
                    numberOfPlaceholderValue++;
                }

                if (valuePlaceholder.equals(iterValue))
                {
                    int len = baseNumber.length();

                    // Convert to int.
                    int intValue = Integer.parseInt(baseNumber) + numberOfPlaceholderValue;

                    String numberOfPlaceholderValueString = Integer.toString(intValue);

                    // Pad with zeros if necessary.
                    while(numberOfPlaceholderValueString.length() < len)
                    {
                        numberOfPlaceholderValueString = "0" + numberOfPlaceholderValueString;
                    }

                    FileTemplate.log("std", "getNumberOfPlaceholderValue(\"" + valuePlaceholder + "\", \"" + baseNumber + "\") = \"" + numberOfPlaceholderValueString + "\" for list value " + name);

                    return numberOfPlaceholderValueString;
                }
                else
                {
                    FileTemplate.log("err", "getNumberOfPlaceholderValue: value \"" + valuePlaceholder + "\" not found in values of placeholder " + name);
                    return null;
                }
            }
        }
        else if (rangePlaceholderValue)
        {
            if (valuePlaceholder == null)
            {
                FileTemplate.log("std", "getNumberOfPlaceholderValue(\"" + valuePlaceholder + "\", \"" + baseNumber + "\") called for range value " + name);
                return baseNumber;
            }
            else
            {
                // Only if no placeholders iterator is available next placeholder is
                // calculated.
                int len = baseNumber.length();

                // Remove leading zeros.
                // while (valuePlaceholder.startsWith("0")) valuePlaceholder =
                // valuePlaceholder.substring(1);
                // if (valuePlaceholder.length() == 0) valuePlaceholder = "0";

                int baseNumberIntValue = Integer.parseInt(baseNumber);

                // Convert to int.
                int valuePlaceholderIntValue = Integer.parseInt(valuePlaceholder);

                int intValue = rangeInverted ? intValueRangeBegin - valuePlaceholderIntValue : valuePlaceholderIntValue - intValueRangeBegin;

                if (intValue < 0 || intValue > Math.abs(intValueRangeEnd - intValueRangeBegin))
                {
                    FileTemplate.log("err", "getNumberOfPlaceholderValue: value \"" + valuePlaceholder + "\" out of range for " + name);
                    return null; // end of sequence reached
                }

                String valuePlaceholderCardinalNumber = Integer.toString(intValue + baseNumberIntValue);

                // Pad with zeros if necessary.
                while(valuePlaceholderCardinalNumber.length() < len)
                {
                    valuePlaceholderCardinalNumber = "0" + valuePlaceholderCardinalNumber;
                }

                //FileTemplate.log("std", "getNumberOfPlaceholderValue(\"" + valuePlaceholder + "\", \"" + baseNumber + "\") = \"" + valuePlaceholderCardinalNumber + "\" for range value " + name);

                return valuePlaceholderCardinalNumber;
            }
        }
        else // singlePlaceholderValue
        {
            //FileTemplate.log("std", "getNumberOfPlaceholderValue(\"" + valuePlaceholder + "\", \"" + baseNumber + "\") called for single value " + name);
            return baseNumber;
        }
    }

    /**
     * ermittelt pb einziger Wert
     * @return 
     */
    public boolean isSinglePlaceholderValue()
    {
        return singlePlaceholderValue;
    }
}
