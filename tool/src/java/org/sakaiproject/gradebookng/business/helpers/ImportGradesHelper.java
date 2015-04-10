package org.sakaiproject.gradebookng.business.helpers;


import au.com.bytecode.opencsv.CSVReader;
import lombok.extern.apachecommons.CommonsLog;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.gradebookng.business.model.ImportColumn;
import org.sakaiproject.gradebookng.business.model.ImportedGrade;
import org.sakaiproject.gradebookng.business.model.ImportedGradeItem;
import org.sakaiproject.gradebookng.business.model.ImportedGradeWrapper;
import org.sakaiproject.gradebookng.business.model.ProcessedGradeItem;
import org.sakaiproject.gradebookng.business.model.ProcessedGradeItemDetail;
import org.sakaiproject.gradebookng.business.model.ProcessedGradeItemStatus;
import org.sakaiproject.gradebookng.tool.model.AssignmentStudentGradeInfo;
import org.sakaiproject.gradebookng.tool.model.GradeInfo;
import org.sakaiproject.gradebookng.tool.model.StudentGradeInfo;
import org.sakaiproject.service.gradebook.shared.Assignment;
import org.sakaiproject.util.BaseResourcePropertiesEdit;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by chmaurer on 1/21/15.
 */
@CommonsLog
public class ImportGradesHelper extends BaseImportHelper {

    private static final String IMPORT_USER_ID="Student ID";
    private static final String IMPORT_USER_NAME="Student Name";

    protected static final String ASSIGNMENT_HEADER_PATTERN = "{0} [{1}]";
    protected static final String ASSIGNMENT_HEADER_COMMENT_PATTERN = "*/ {0} Comments */";
    protected static final String HEADER_STANDARD_PATTERN = "{0}";


    /**
     * Parse a CSV into a list of ImportedGrade objects. Returns list if ok, or null if error
     * @param is InputStream of the data to parse
     * @return
     */
    public static ImportedGradeWrapper parseCsv(InputStream is) {

        //manually parse method so we can support arbitrary columns
        CSVReader reader = new CSVReader(new InputStreamReader(is));
        String [] nextLine;
        int lineCount = 0;
        List<ImportedGrade> list = new ArrayList<ImportedGrade>();
        Map<Integer,ImportColumn> mapping = null;

        try {
            while ((nextLine = reader.readNext()) != null) {

                if(lineCount == 0) {
                    //header row, capture it
                    mapping = mapHeaderRow(nextLine);
                } else {
                    //map the fields into the object
                    list.add(mapLine(nextLine, mapping));
                }
                lineCount++;
            }
        } catch (Exception e) {
            log.error("Error reading imported file: " + e.getClass() + " : " + e.getMessage());
            return null;
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        ImportedGradeWrapper importedGradeWrapper = new ImportedGradeWrapper();
        importedGradeWrapper.setColumns(mapping.values());
        importedGradeWrapper.setImportedGrades(list);

        return importedGradeWrapper;
    }

    /**
     * Parse an XLS into a list of ImportedGrade objects
     * Note that only the first sheet of the Excel file is supported.
     *
     * @param is InputStream of the data to parse
     * @return
     */
    public static ImportedGradeWrapper parseXls(InputStream is) {

        int lineCount = 0;
        List<ImportedGrade> list = new ArrayList<ImportedGrade>();
        Map<Integer,ImportColumn> mapping = null;

        try {
            Workbook wb = WorkbookFactory.create(is);
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {

                String[] r = convertRow(row);

                if(lineCount == 0) {
                    //header row, capture it
                    mapping = mapHeaderRow(r);
                } else {
                    //map the fields into the object
                    list.add(mapLine(r, mapping));
                }
                lineCount++;
            }

        } catch (Exception e) {
            log.error("Error reading imported file: " + e.getClass() + " : " + e.getMessage());
            return null;
        }

        ImportedGradeWrapper importedGradeWrapper = new ImportedGradeWrapper();
        importedGradeWrapper.setColumns(mapping.values());
        importedGradeWrapper.setImportedGrades(list);
        return importedGradeWrapper;
    }

//    private static List<ProcessedGradeItem> processAssignmentNames(Map<Integer,String> mapping) {
//        List<String> assignmentNames = new ArrayList<String>();
//        for(Map.Entry<Integer,String> entry: mapping.entrySet()) {
//            int i = entry.getKey();
//            //trim in case some whitespace crept in
//            String col = trim(entry.getValue());
//
//            //Find all columns that are not well known
//            if(!StringUtils.equals(col, IMPORT_USER_ID) && !StringUtils.equals(col, IMPORT_USER_NAME)) {
//
//                String assignmentName = parseHeaderForAssignmentName(col);
//                if (!assignmentNames.contains(assignmentName))
//                    assignmentNames.add(assignmentName);
//            }
//        }
//        return assignmentNames;
//    }

    private static Object[] parseHeaderForAssignmentName(String headerValue) {
        MessageFormat mf = new MessageFormat(ImportGradesHelper.ASSIGNMENT_HEADER_PATTERN);
        Object[] parsedObject;
        try {
            parsedObject = mf.parse(headerValue);
        } catch (ParseException e) {
            mf = new MessageFormat(ImportGradesHelper.ASSIGNMENT_HEADER_COMMENT_PATTERN);
            try {
                parsedObject = mf.parse(headerValue);
            } catch (ParseException e1) {
                throw new RuntimeException("Error parsing grade import");
            }
        }

        return parsedObject;
    }

    private static boolean isCommentsColumn(String headerValue) {
        MessageFormat mf = new MessageFormat(ImportGradesHelper.ASSIGNMENT_HEADER_COMMENT_PATTERN);
        try {
            mf.parse(headerValue);
        } catch (ParseException e) {
            return false;
        }
        return true;
    }

    private static boolean isGradeColumn(String headerValue) {
        MessageFormat mf = new MessageFormat(ImportGradesHelper.ASSIGNMENT_HEADER_PATTERN);
        try {
            mf.parse(headerValue);
        } catch (ParseException e) {
            return false;
        }
        return true;
    }

    /**
     * Takes a row of data and maps it into the appropriate ImportedGrade properties
     * We have a fixed list of properties, anything else goes into ResourceProperties
     * @param line
     * @param mapping
     * @return
     */
    private static ImportedGrade mapLine(String[] line, Map<Integer,ImportColumn> mapping){

        ImportedGrade grade = new ImportedGrade();
        ResourceProperties p = new BaseResourcePropertiesEdit();

        for(Map.Entry<Integer,ImportColumn> entry: mapping.entrySet()) {
            int i = entry.getKey();
            //trim in case some whitespace crept in
            ImportColumn importColumn = entry.getValue();
//            String col = trim(entry.getValue());



            //now check each of the main properties in turn to determine which one to set, otherwise set into props
            if(StringUtils.equals(importColumn.getColumnTitle(), IMPORT_USER_ID)) {
                grade.setStudentId(trim(line[i]));
            } else if(StringUtils.equals(importColumn.getColumnTitle(), IMPORT_USER_NAME)) {
                grade.setStudentName(trim(line[i]));
            } else if(ImportColumn.TYPE_ITEM_WITH_POINTS==importColumn.getType()) {
                String assignmentName = importColumn.getColumnTitle();
                ImportedGradeItem importedGradeItem = grade.getGradeItemMap().get(assignmentName);
                if (importedGradeItem == null) {
                    importedGradeItem = new ImportedGradeItem();
                    grade.getGradeItemMap().put(assignmentName, importedGradeItem);
                    importedGradeItem.setGradeItemName(assignmentName);
                }
                importedGradeItem.setGradeItemScore(trim(line[i]));
            } else if(ImportColumn.TYPE_ITEM_WITH_COMMENTS==importColumn.getType()) {
                String assignmentName = importColumn.getColumnTitle();
                ImportedGradeItem importedGradeItem = grade.getGradeItemMap().get(assignmentName);
                if (importedGradeItem == null) {
                    importedGradeItem = new ImportedGradeItem();
                    grade.getGradeItemMap().put(assignmentName, importedGradeItem);
                    importedGradeItem.setGradeItemName(assignmentName);
                }
                importedGradeItem.setGradeItemComment(trim(line[i]));
            } else {

                //only add if not blank
                if(StringUtils.isNotBlank(trim(line[i]))) {
                    p.addProperty(importColumn.getColumnTitle(), trim(line[i]));
                }
            }
        }

        grade.setProperties(p);
        return grade;
    }


    public static List<ProcessedGradeItem> processImportedGrades(ImportedGradeWrapper importedGradeWrapper,
                                                                 List<Assignment> assignments, List<StudentGradeInfo> currentGrades) {
        List<ProcessedGradeItem> processedGradeItems = new ArrayList<ProcessedGradeItem>();
        Map<String, Assignment> assignmentNameMap = new HashMap<String, Assignment>();

        Map<Long, AssignmentStudentGradeInfo> transformedGradeMap = transformCurrentGrades(currentGrades);

        //Map the assignment name back to the Id
        for (Assignment assignment : assignments) {
            assignmentNameMap.put(assignment.getName(), assignment);
        }


        for (ImportColumn column : importedGradeWrapper.getColumns()) {
            ProcessedGradeItem processedGradeItem = new ProcessedGradeItem();

            String assignmentName = column.getColumnTitle();

            if (column.getType() == ImportColumn.TYPE_ITEM_WITH_POINTS) {
                processedGradeItem.setItemTitle(assignmentName);
                processedGradeItem.setItemPointValue(column.getPoints());



            } else if (column.getType() == ImportColumn.TYPE_ITEM_WITH_COMMENTS) {
                processedGradeItem.setItemTitle(assignmentName + " Comments");
                processedGradeItem.setItemPointValue("N/A");

            } else {
                //Just get out
                log.warn("Bad column type - " + column.getType() + ".  Skipping.");
                continue;
            }

            Assignment assignment = assignmentNameMap.get(assignmentName);

            ProcessedGradeItemStatus status = determineStatus(column, assignment, importedGradeWrapper, transformedGradeMap);
            processedGradeItem.setStatus(status);

            if (assignment != null) {
                processedGradeItem.setItemId(assignment.getId());
            }

            List<ProcessedGradeItemDetail> processedGradeItemDetails = new ArrayList<>();
            for (ImportedGrade importedGrade : importedGradeWrapper.getImportedGrades()) {
                ImportedGradeItem importedGradeItem = importedGrade.getGradeItemMap().get(assignmentName);
                if (importedGradeItem != null) {
                    ProcessedGradeItemDetail processedGradeItemDetail = new ProcessedGradeItemDetail();
                    processedGradeItemDetail.setStudentId(importedGrade.getStudentId());
                    processedGradeItemDetail.setGrade(importedGradeItem.getGradeItemScore());
                }

            }
            processedGradeItem.setProcessedGradeItemDetails(processedGradeItemDetails);

            processedGradeItems.add(processedGradeItem);
        }

        return processedGradeItems;

    }

    private static ProcessedGradeItemStatus determineStatus(ImportColumn column, Assignment assignment, ImportedGradeWrapper importedGradeWrapper,
                                       Map<Long, AssignmentStudentGradeInfo> transformedGradeMap) {
        ProcessedGradeItemStatus status = new ProcessedGradeItemStatus(ProcessedGradeItemStatus.STATUS_UNKNOWN);
        if (assignment == null) {
            status = new ProcessedGradeItemStatus(ProcessedGradeItemStatus.STATUS_NEW);
        } else if (assignment.getExternalId() != null) {
            status = new ProcessedGradeItemStatus(ProcessedGradeItemStatus.STATUS_EXTERNAL, assignment.getExternalAppName());
        } else {
            for (ImportedGrade importedGrade : importedGradeWrapper.getImportedGrades()) {
                AssignmentStudentGradeInfo assignmentStudentGradeInfo = transformedGradeMap.get(assignment.getId());
                ImportedGradeItem importedGradeItem = importedGrade.getGradeItemMap().get(column.getColumnTitle());
                GradeInfo actualGradeInfo = assignmentStudentGradeInfo.getStudentGrades().get(importedGrade.getStudentId());

                String actualScore = null;
                String actualComment = null;

                if (actualGradeInfo != null) {
                    actualScore = actualGradeInfo.getGrade();
                    actualComment = actualGradeInfo.getGradeComment();
                }
                String importedScore = null;
                String importedComment = null;

                if (importedGradeItem != null) {
                    importedScore = importedGradeItem.getGradeItemScore();
                    importedComment = importedGradeItem.getGradeItemComment();
                }

                if (column.getType() == ImportColumn.TYPE_ITEM_WITH_POINTS) {
                    if (importedScore != null && !importedScore.equals(actualScore)) {
                        status = new ProcessedGradeItemStatus(ProcessedGradeItemStatus.STATUS_UPDATE);
                        break;
                    }
                } else if (column.getType() == ImportColumn.TYPE_ITEM_WITH_COMMENTS) {
                    if (importedComment != null && !importedComment.equals(actualComment)) {
                        status = new ProcessedGradeItemStatus(ProcessedGradeItemStatus.STATUS_UPDATE);
                        break;
                    }
                }
            }
            // If we get here, must not have been any changes
            if (status.getStatusCode() == ProcessedGradeItemStatus.STATUS_UNKNOWN) {
                status = new ProcessedGradeItemStatus(ProcessedGradeItemStatus.STATUS_NA);
            }

            //TODO - What about if a user was added to the import file?
            // That probably means that actualGradeInfo from up above is null...but what do I do?

        }
        return status;
    }

    private static Map<Long, AssignmentStudentGradeInfo> transformCurrentGrades(List<StudentGradeInfo> currentGrades) {
        Map<Long, AssignmentStudentGradeInfo> assignmentMap = new HashMap<Long, AssignmentStudentGradeInfo>();

        for (StudentGradeInfo studentGradeInfo : currentGrades) {
            for (Map.Entry<Long, GradeInfo> entry : studentGradeInfo.getGrades().entrySet()) {
                Long assignmentId = entry.getKey();
                AssignmentStudentGradeInfo assignmentStudentGradeInfo = assignmentMap.get(assignmentId);
                if (assignmentStudentGradeInfo == null) {
                    assignmentStudentGradeInfo = new AssignmentStudentGradeInfo();
                    assignmentStudentGradeInfo.setAssignmemtId(assignmentId);
                    assignmentMap.put(assignmentId, assignmentStudentGradeInfo);
                }
                assignmentStudentGradeInfo.addGrade(studentGradeInfo.getStudentUuid(), entry.getValue());
//                assignmentStudentGradeInfo.setGradeInfo(entry.getValue());
//                assignmentStudentGradeInfo.setStudentId(studentGradeInfo.getStudentUuid());
            }

        }

        return assignmentMap;
    }
}
