 /*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.spring.transaction.controller;

import com.spring.admin.model.FunctionButton;
import com.spring.admin.service.SystemManager;
import com.spring.admin.service.UserSystemManager;
import com.spring.maint.controller.ErrorMessageController;
import com.spring.maint.service.ErrorMessageManager;
import com.spring.audit.aspectj.Audit;
import com.spring.transaction.webclient.NACHAViewClient;
import com.spring.login.model.UserSession;
import com.spring.maint.service.TransactionStatusManager;
import com.spring.standard.service.StdCodeManager;
import com.spring.transaction.model.TransactionsNachaHeader;
import com.spring.util.ControllerUtils;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import my.com.finexus.logger.Log;
import my.com.finexus.security.Validator;
import my.com.finexus.util.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.bind.annotation.SessionAttributes;
import com.spring.transaction.service.NachaFileSummaryAndTransactionManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import net.sf.jasperreports.engine.JRAbstractExporter;
import net.sf.jasperreports.engine.JRExporterParameter;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.JRHtmlExporter;
import net.sf.jasperreports.engine.export.JRHtmlExporterParameter;
import net.sf.jasperreports.engine.export.JRPdfExporter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import webservice.core.nachaview.NACHAFILE;
import webservice.core.nachaview.NACHAFILERecord;

/**
 *
 * @author TomKT
 */
@Controller
@SessionAttributes("UserSession")
@RequestMapping("transaction/NachaFileSummaryAndTransaction")
public class NachaFileSummaryAndTransactionController {

    @Autowired
    SystemManager systemManager;
    @Autowired
    ErrorMessageManager errorMessageManager;
    @Autowired
    NachaFileSummaryAndTransactionManager nachaFileSummaryAndTransactionManager;
    @Autowired
    UserSystemManager userSystemManager;
    @Autowired
    StdCodeManager stdCodeManager;
    @Autowired
    TransactionStatusManager transactionStatusManager;

    private final int PDF_LR_MAX_LEN_SUMMARY = 135;
    private final int PDF_LR_MAX_LEN_TRANSACTION = 94;
    private final int HTML_LR_MAX_LEN_TRANSACTION = 98;
    private final int HTML_LR_MAX_LEN_SUMMARY = 135;

    private String errorMsg = "";
    private String gfJrxmlPath = "/../applications/IBG/WEB-INF/jrxml/";

    Validator validator = new Validator();
    ErrorMessageController errorMessage = new ErrorMessageController();
    ControllerUtils cUtils = new ControllerUtils();

    @Audit("Transaction > Nacha File Summary And Transaction - Browse")
    @RequestMapping(value = "/Browse", method = RequestMethod.POST)
    public String redirectNachaFileSummaryAndTransactionBrowse(@SessionAttribute("UserSession") UserSession userSession, HttpSession session, Model model, @RequestParam Map<String, String> requestParams, HttpServletRequest request) {
        try {
            boolean forward = (request.getAttribute("forward") != null && request.getAttribute("forward").equals("true")) || (requestParams.get("cancel") != null && requestParams.get("cancel").equals("true"));

            TransactionsNachaHeader advSearchTrxHeader = new TransactionsNachaHeader();
            if (!forward) {
                advSearchTrxHeader = validateAdvSearch(requestParams);
                if (advSearchTrxHeader.getSettValDt() != null && advSearchTrxHeader.getSettValDt().isEmpty()) {
                    model.addAttribute("Transaction_Nacha_File_Summary_And_Transaction_Value_Date", advSearchTrxHeader.getAppValDt());
                } else {
                    model.addAttribute("Transaction_Nacha_File_Summary_And_Transaction_Value_Date", advSearchTrxHeader.getSettValDt());
                }
            } else {
                model.addAttribute("Transaction_Nacha_File_Summary_And_Transaction_Value_Date", systemManager.getSystemSecurity().getApplValDt());
            }

            List<String> assignedSystem = userSystemManager.getAssignedSystemById(userSession.getUsrId());

            List<String>[] trxStatusGroup = transactionStatusManager.getTransactionStatusListByGroup();
            model.addAttribute("trxStatusGroup", Arrays.toString(trxStatusGroup));

            List<TransactionsNachaHeader> trxHeaders = nachaFileSummaryAndTransactionManager.getNachaFileSummaryAndTransaction(advSearchTrxHeader, assignedSystem);
            formatList(trxHeaders);
            String cPath = "transaction/NachaFileSummaryAndTransaction";
            String menuId = requestParams.get("menuId");

            List<FunctionButton> funcBtns = systemManager.getFuncBtn(userSession.getUsrId(), menuId);

            model.addAttribute("auditTRec", "[" + trxHeaders.size() + "]");
            cUtils.setBrowseModel(trxHeaders, funcBtns, cPath, menuId, model);

        } catch (Exception e) {
            Log.exception("NachaFileSummaryAndTransactionController", e);
        }
        return "transaction/NachaFileSummaryAndTransaction-Browse";
    }

    @Audit("Transaction > Nacha File Summary And Transaction PDF Report > Download")
    @RequestMapping(value = "/{stpMsgId}/Download", method = RequestMethod.POST)
    public String redirectDownloadZip(@SessionAttribute("UserSession") UserSession userSession, @PathVariable String stpMsgId, HttpSession session, Model model, @RequestParam Map<String, String> requestParams, HttpServletResponse response) {

        String downloadFileName = DateTime.getCurrentDateTime("yyyyMMdd_HHmmss").concat("_IBG_Report.zip");
        try {
            byte[] generatedZipFiles = zipFiles(stpMsgId, userSession, response);

            if (generatedZipFiles.length > 0) {
                response.setContentType("application/zip");
                response.setHeader("Content-Disposition", "attachment; filename=\"" + downloadFileName + "\"");

                ServletOutputStream sos = response.getOutputStream();

                sos.write(generatedZipFiles);
                sos.flush();
                sos.close();
                response.getOutputStream().flush();
                response.getOutputStream().close();
            }
        } catch (IOException e) {
            Log.exception("NachaFileSummaryAndTransactionController", e);
        } catch (Exception ex) {
            Log.exception("NachaFileSummaryAndTransactionController", ex);
        }
        
        return "forward:/transaction/NachaFileSummaryAndTransaction/Browse";
        
        //redirectNachaFileSummaryAndTransactionBrowse(userSession, session, model, requestParams, null);
    }

    @RequestMapping(value = "/{stpMsgId}/{task}/Validate", method = RequestMethod.POST)
    public @ResponseBody
    List<String> validateIBGReportDownloadTask(@SessionAttribute("UserSession") UserSession userSession, @PathVariable String stpMsgId, @PathVariable String task, @RequestBody(required = false) String requestBody) {
        List<String> response = new ArrayList<>();
        if (stpMsgId.split("\\|").length > 30) {
            response.add("false");
            response.add("Selected file count exceeded 30. Please select file not more than 30");
        } else {
            response.add("true");
        }
        return response;
    }

    @Audit("Transaction > Nacha File Summary And Transaction > Summary Message > View")
    @RequestMapping(value = "/SummaryMessage/{stpMsgId}/View", method = RequestMethod.POST)
    public String redirectSummaryMessageView(@SessionAttribute("UserSession") UserSession userSession, @PathVariable String stpMsgId, Model model, HttpSession session, @RequestParam Map<String, String> requestParams, HttpServletRequest httpServletRequest) {
        try {

            String rtpFileType = "html";
            SummaryMessageView(userSession, model, stpMsgId, rtpFileType, null);
            return "frmMessageDetail3";

        } catch (Exception e) {
            Log.exception("NachaFileSummaryAndTransactionController", e);
        }
        return "frmMessageDetail3";
    }

    @Audit("Transaction > Nacha File Summary And Transaction > Summary Message > Save As PDF")
    @RequestMapping(value = "/SummaryMessage/{stpMsgId}/SaveAsPdf", method = RequestMethod.POST)
    public void redirectSummaryMessageSaveAsPdf(@SessionAttribute("UserSession") UserSession userSession, @PathVariable String stpMsgId, Model model, @RequestParam Map<String, String> requestParams, HttpServletRequest request, HttpServletResponse response) {
        try {
            String messageType = requestParams.get("messageType");
            if ("Summary".equalsIgnoreCase(messageType)) {
                String rtpFileType = "pdf";
                SummaryMessageView(userSession, model, stpMsgId, rtpFileType, response);
            }

        } catch (Exception e) {
            Log.exception("NachaFileSummaryAndTransactionController", e);

        }
    }

    private void SummaryMessageView(UserSession userSession, Model model, String msgId, String fileType, HttpServletResponse response) {
        String messageType = "Summary";
        try {

            Map<String, Object> params = new HashMap<String, Object>();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JRHtmlExporter exporter = new JRHtmlExporter();

            String printedTime = DateTime.formatDate(DateTime.parseDate(DateTime.getCurrentTimestamp(), "yyyy-MM-dd HH:mm:ss"), "dd-MM-yyyy HH:mm:ss");

            String userDir = this.getClass().getResource("").getPath();
            String jasperFilePath = "";
            if (userDir.contains("build")) {
                jasperFilePath = userDir.substring(0, userDir.indexOf("build")) + "/web/WEB-INF/jrxml/";
            } else {
                jasperFilePath = System.getProperty("user.dir") + gfJrxmlPath;
            }
            if ("html".equalsIgnoreCase(fileType)) {
                int maxLen = HTML_LR_MAX_LEN_SUMMARY;
                String userIdDateTime = alignmentLeftAndRight("User ID : " + userSession.getUsrId(), "Printed Time : " + printedTime, maxLen);
                String ReportTitle = alignmentToCenter("Nacha File Summary", maxLen);
                String CenterMsgId = alignmentToCenter("Message ID - " + msgId, maxLen);

                params.put("userIdAndDateTime", userIdDateTime);
                params.put("rptTitle", ReportTitle);
                params.put("stpMsgId", CenterMsgId);

                String MsgResponse = getNachaViewResponse(msgId, messageType);
                params.put("MessageResponse", MsgResponse);

                jasperFilePath = jasperFilePath + "NachaSummaryMessage_HTML.jasper";
                JasperPrint jasperPrint = JasperFillManager.fillReport(jasperFilePath, params);

                exporter.setParameter(JRExporterParameter.JASPER_PRINT, jasperPrint);
                exporter.setParameter(JRExporterParameter.OUTPUT_STREAM, baos);
                exporter.setParameter(JRHtmlExporterParameter.IS_USING_IMAGES_TO_ALIGN, false);
                exporter.exportReport();

                String htmlCode = new String(baos.toByteArray());
                baos.close();

                String strRegEx = "(?i)<[\\s]*[/]?[\\s]*p[^>]*>";
                htmlCode = htmlCode.replaceAll(strRegEx, "");

                model.addAttribute("htmlCode", htmlCode);
                model.addAttribute("stpMsgId", msgId);
                model.addAttribute("msgTitle", msgId);
                model.addAttribute("messageType", messageType);

            } else if ("pdf".equalsIgnoreCase(fileType)) {
                int maxLen = PDF_LR_MAX_LEN_SUMMARY;
                String userIdDateTime = alignmentLeftAndRight("User ID : " + userSession.getUsrId(), "Printed Time : " + printedTime, maxLen);
                String CenterMsgId = alignmentToCenter("Message ID - " + msgId, maxLen);
                params.put("userIdAndDateTime", userIdDateTime);

                String ReportTitle = alignmentToCenter("Nacha File Summary", maxLen);
                params.put("userIdAndDateTime", userIdDateTime);
                params.put("rptTitle", ReportTitle);
                params.put("stpMsgId", CenterMsgId);

                String MsgResponse = getNachaViewResponse(msgId, messageType);
                params.put("MessageResponse", MsgResponse);

                jasperFilePath = jasperFilePath + "NachaSummaryMessage_PDF.jasper";

                JasperPrint jasperPrint = JasperFillManager.fillReport(jasperFilePath, params);

                JRAbstractExporter exporterPDF = new JRPdfExporter();
                exporterPDF.setParameter(JRExporterParameter.JASPER_PRINT, jasperPrint);
                exporterPDF.setParameter(JRExporterParameter.OUTPUT_STREAM, response.getOutputStream());
                response.setHeader("Content-Disposition", "attachment; filename=" + msgId + "_" + printedTime.replaceAll(":", "").replaceAll(" ", "_") + ".pdf");
                response.setContentType("application/pdf");
                exporterPDF.exportReport();

            }

            model.addAttribute("sToken", userSession.getsToken());
        } catch (Exception e) {
            Log.exception("NachaFileSummaryAndTransactionController", e);
        }
    }

    @Audit("Transaction > Nacha File Summary And Transaction > Transaction Message > View")
    @RequestMapping(value = "/TransactionMessage/{stpMsgId}/View", method = RequestMethod.POST)
    public String redirectTransactionMessageView(@SessionAttribute("UserSession") UserSession userSession, @PathVariable String stpMsgId, HttpSession session, Model model, @RequestParam Map<String, String> requestParams, HttpServletRequest httpServletRequest) {
        try {
            String rtpFileType = "html";
            TransactionMessageView(userSession, model, stpMsgId, rtpFileType, null);
            return "frmMessageDetail3";

        } catch (Exception e) {
            Log.exception("NachaFileSummaryAndTransactionController", e);
        }
        return "frmMessageDetail3";
    }

    @Audit("Transaction > Nacha File Summary And Transaction > Transaction Message > Save As PDF")
    @RequestMapping(value = "/TransactionMessage/{stpMsgId}/SaveAsPdf", method = RequestMethod.POST)
    public void redirectTransactionMessageSaveAsPdf(@SessionAttribute("UserSession") UserSession userSession, @PathVariable String stpMsgId, Model model, @RequestParam Map<String, String> requestParams, HttpServletRequest request, HttpServletResponse response) {
        try {
            String rtpFileType = "pdf";
            TransactionMessageView(userSession, model, stpMsgId, rtpFileType, response);

        } catch (Exception e) {
            Log.exception("NachaFileSummaryAndTransactionController", e);

        }
    }

    private void TransactionMessageView(UserSession userSession, Model model, String msgId, String fileType, HttpServletResponse response) {
        String messageType = "Transaction";

        try {
            Map<String, Object> params = new HashMap<String, Object>();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JRHtmlExporter exporter = new JRHtmlExporter();

            String printedTime = DateTime.formatDate(DateTime.parseDate(DateTime.getCurrentTimestamp(), "yyyy-MM-dd HH:mm:ss"), "dd-MM-yyyy HH:mm:ss");

            String userDir = this.getClass().getResource("").getPath();
            String jasperFilePath = "";
            if (userDir.contains("build")) {
                jasperFilePath = userDir.substring(0, userDir.indexOf("build")) + "/web/WEB-INF/jrxml/";
            } else {
                jasperFilePath = System.getProperty("user.dir") + gfJrxmlPath;
            }
            if ("html".equalsIgnoreCase(fileType)) {
                int maxLen = HTML_LR_MAX_LEN_TRANSACTION;
                String userIdDateTime = alignmentLeftAndRight("User ID : " + userSession.getUsrId(), "Printed Time : " + printedTime, maxLen);
                String ReportTitle = alignmentToCenter("Nacha File Transaction", maxLen);
                String CenterMsgId = alignmentToCenter("Message ID - " + msgId, maxLen);
                params.put("userIdAndDateTime", userIdDateTime);
                params.put("rptTitle", ReportTitle);
                params.put("stpMsgId", CenterMsgId);

                String MsgResponse = getNachaViewResponse(msgId, messageType);
                params.put("MessageResponse", MsgResponse);

                jasperFilePath = jasperFilePath + "NachaTransactionMessage_HTML.jasper";
                JasperPrint jasperPrint = JasperFillManager.fillReport(jasperFilePath, params);

                exporter.setParameter(JRExporterParameter.JASPER_PRINT, jasperPrint);
                exporter.setParameter(JRExporterParameter.OUTPUT_STREAM, baos);
                exporter.setParameter(JRHtmlExporterParameter.IS_USING_IMAGES_TO_ALIGN, false);
                exporter.exportReport();

                String htmlCode = new String(baos.toByteArray());
                baos.close();

                String strRegEx = "(?i)<[\\s]*[/]?[\\s]*p[^>]*>";
                htmlCode = htmlCode.replaceAll(strRegEx, "");

                model.addAttribute("htmlCode", htmlCode);
                model.addAttribute("stpMsgId", msgId);
                model.addAttribute("msgTitle", msgId);
                model.addAttribute("messageType", messageType);

            } else if ("pdf".equalsIgnoreCase(fileType)) {
                int maxLen = PDF_LR_MAX_LEN_TRANSACTION;
                String userIdDateTime = alignmentLeftAndRight("User ID : " + userSession.getUsrId(), "Printed Time : " + printedTime, maxLen);
                String CenterMsgId = alignmentToCenter("Message ID - " + msgId, maxLen);
                params.put("userIdAndDateTime", userIdDateTime);

                String ReportTitle = alignmentToCenter("Nacha File Transaction", maxLen);
                params.put("userIdAndDateTime", userIdDateTime);
                params.put("rptTitle", ReportTitle);
                params.put("stpMsgId", CenterMsgId);

                String MsgResponse = getNachaViewResponse(msgId, messageType);
                params.put("MessageResponse", MsgResponse);

                jasperFilePath = jasperFilePath + "NachaTransactionMessage_PDF.jasper";

                JasperPrint jasperPrint = JasperFillManager.fillReport(jasperFilePath, params);

                JRAbstractExporter exporterPDF = new JRPdfExporter();
                exporterPDF.setParameter(JRExporterParameter.JASPER_PRINT, jasperPrint);
                exporterPDF.setParameter(JRExporterParameter.OUTPUT_STREAM, response.getOutputStream());
                response.setHeader("Content-Disposition", "attachment; filename=" + msgId + "_" + printedTime.replaceAll(":", "").replaceAll(" ", "_") + ".pdf");
                response.setContentType("application/pdf");
                exporterPDF.exportReport();
            }

            model.addAttribute("sToken", userSession.getsToken());

        } catch (Exception e) {
            Log.exception("NachaFileSummaryAndTransactionController", e);
        }
    }

    public byte[] zipFiles(String stpMsgId, UserSession userSession, HttpServletResponse response) throws Exception {

        byte[] buffer = new byte[1024];
        Map<String, Object> params = new HashMap<String, Object>();
        ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
        ByteArrayOutputStream pdfBaos = new ByteArrayOutputStream();
        int length;

        String printedTime = DateTime.formatDate(DateTime.parseDate(DateTime.getCurrentTimestamp(), "yyyy-MM-dd HH:mm:ss"), "dd-MM-yyyy HH:mm:ss");
        String userDir = this.getClass().getResource("").getPath();
        String jasperFilePath = "";
        String trxType = "";
        String jasperPath = "";

        if (userDir.contains("build")) {
            jasperFilePath = userDir.substring(0, userDir.indexOf("build")) + "/web/WEB-INF/jrxml/";
        } else {
            jasperFilePath = System.getProperty("user.dir") + gfJrxmlPath;
        }

        try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
            ByteArrayInputStream fis;
            List<String> stpMsgIdList = new ArrayList<>();

            if (StringUtils.countOccurrencesOf(stpMsgId, "|") < 1) {
                stpMsgIdList.add(stpMsgId);
            } else {
                for (String msgId : stpMsgId.split("\\|")) {
                    stpMsgIdList.add(msgId);
                }
            }

            for (int j = 0; j < stpMsgIdList.size(); j++) {
                pdfBaos.reset();
                if (stpMsgIdList.get(j).charAt(0) == 'I') {
                    trxType = "Incoming";
                } else {
                    trxType = "Outgoing";
                }

                String userIdDateTimeTrx = alignmentLeftAndRight("User ID : " + userSession.getUsrId(), "Printed Time : " + printedTime, PDF_LR_MAX_LEN_TRANSACTION);
                String TrxMsgCenterMsgId = alignmentToCenter("Message ID - " + stpMsgIdList.get(j), PDF_LR_MAX_LEN_TRANSACTION);
                String TrxMsgReportTitle = alignmentToCenter("Nacha File Transaction", PDF_LR_MAX_LEN_TRANSACTION);
                params.put("userIdAndDateTime", userIdDateTimeTrx);
                params.put("rptTitle", TrxMsgReportTitle);
                params.put("stpMsgId", TrxMsgCenterMsgId);

                String TrxMsgResponse = getNachaViewResponse(stpMsgIdList.get(j), "Transaction");
                params.put("MessageResponse", TrxMsgResponse);

                jasperPath = jasperFilePath + "NachaTransactionMessage_PDF.jasper";
                JasperPrint jasperPrintTrxMsg = JasperFillManager.fillReport(jasperPath, params);

                JRAbstractExporter exporterPDFTrx = new JRPdfExporter();
                exporterPDFTrx.setParameter(JRExporterParameter.JASPER_PRINT, jasperPrintTrxMsg);
                exporterPDFTrx.setParameter(JRExporterParameter.OUTPUT_STREAM, pdfBaos);
                response.setHeader("Content-Disposition", "attachment; filename=" + stpMsgIdList.get(j) + "_" + printedTime.replaceAll(":", "").replaceAll(" ", "_") + ".pdf");
                response.setContentType("application/pdf");
                exporterPDFTrx.exportReport();
                
                fis = new ByteArrayInputStream(pdfBaos.toByteArray()); //baos from jasperreport               
                String Trxentryname = stpMsgIdList.get(j) + "_Transaction_Message_" + printedTime.replaceAll(":", "").replaceAll(" ", "_") + ".pdf";

                zos.putNextEntry(new ZipEntry(Trxentryname));
                while ((length = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, length);
                }
                zos.closeEntry();
                fis.close();

                if (trxType.equals("Outgoing")) {
                    pdfBaos.reset();
                    String userIdDateTimesSummary = alignmentLeftAndRight("User ID : " + userSession.getUsrId(), "Printed Time : " + printedTime, PDF_LR_MAX_LEN_SUMMARY);
                    String CenterOutgoingMsgId = alignmentToCenter("Message ID - " + stpMsgIdList.get(j), PDF_LR_MAX_LEN_SUMMARY);
                    String SummaryMsgReportTitle = alignmentToCenter("Nacha File Summary", PDF_LR_MAX_LEN_SUMMARY);
                    params.put("userIdAndDateTime", userIdDateTimesSummary);
                    params.put("rptTitle", SummaryMsgReportTitle);
                    params.put("stpMsgId", CenterOutgoingMsgId);

                    String SummaryMsgResponse = getNachaViewResponse(stpMsgIdList.get(j), "Summary");
                    params.put("MessageResponse", SummaryMsgResponse);

                    jasperPath = jasperFilePath + "NachaSummaryMessage_PDF.jasper";
                    JasperPrint jasperPrintSummaryMsg = JasperFillManager.fillReport(jasperPath, params);

                    JRAbstractExporter exporterPDFSummary = new JRPdfExporter();
                    exporterPDFSummary.setParameter(JRExporterParameter.JASPER_PRINT, jasperPrintSummaryMsg);
                    exporterPDFSummary.setParameter(JRExporterParameter.OUTPUT_STREAM, pdfBaos);
                    response.setHeader("Content-Disposition", "attachment; filename=" + stpMsgIdList.get(j) + "_" + printedTime.replaceAll(":", "").replaceAll(" ", "_") + ".pdf");
                    response.setContentType("application/pdf");
                    exporterPDFSummary.exportReport();

                    fis = new ByteArrayInputStream(pdfBaos.toByteArray()); //baos from jasperreport               
                    String SummaryEntryname = stpMsgIdList.get(j) + "_Summary_Message_" + printedTime.replaceAll(":", "").replaceAll(" ", "_") + ".pdf";

                    zos.putNextEntry(new ZipEntry(SummaryEntryname));
                    
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }
                    zos.closeEntry();
                    fis.close();
                }

            }

        } catch (ZipException ex) {
            Log.exception("NachaFileSummaryAndTransactionController", ex);
            zipBaos.reset();
            return zipBaos.toByteArray();
        }
        return zipBaos.toByteArray();
    }

    private String alignmentLeftAndRight(String leftValue, String rightValue, int maxLen) {
        String value = "";
        if (!leftValue.isEmpty() && !rightValue.isEmpty()) {
            String str = leftValue + " " + rightValue;
            if (str.length() > maxLen) {
                value = str.substring(0, maxLen);
            } else {
                int numOfSpace = maxLen - leftValue.length() - rightValue.length();
                value = leftValue;
                for (int i = 0; i < numOfSpace; i++) {
                    value += " ";
                }
                value += rightValue;
            }
        }
        return value;
    }

    private String alignmentToCenter(String value, int maxLen) {
        if (!value.isEmpty()) {
            if (value.length() > maxLen) {
                value = value.substring(0, maxLen);
            } else {
                int numOfSpace = (maxLen / 2) - (value.length() / 2);

                for (int i = 0; i < numOfSpace; i++) {
                    value = " " + value;
                }
            }
        }
        return value;
    }

    private String getNachaViewResponse(String stpMsgId, String messageType) throws Exception {

        NACHAFILE request = new NACHAFILE();
        NACHAFILERecord nachaFileRecord = new NACHAFILERecord();
        NACHAFILERecord.NACHAMSGID nachaMsgId = new NACHAFILERecord.NACHAMSGID();
        NACHAFILERecord.TYPE type = new NACHAFILERecord.TYPE();
        type.setValue(messageType);
        nachaMsgId.setValue(stpMsgId);
        nachaFileRecord.setNACHAMSGID(nachaMsgId);
        nachaFileRecord.setTYPE(type);

        request.getNACHAFILERecord().clear();
        request.getNACHAFILERecord().add(nachaFileRecord);

        String result = NACHAViewClient.callNacha(request);
        StringBuilder sb = new StringBuilder(result);
        sb.deleteCharAt(0);
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();

    }

    private TransactionsNachaHeader validateAdvSearch(Map<String, String> requestParams) throws Exception {
        String settValDt = "";
        if (requestParams.get("Transaction_Nacha_File_Summary_And_Transaction_Value_Date") != null) {
            settValDt = DateTime.formatDate(DateTime.parseDate(requestParams.get("Transaction_Nacha_File_Summary_And_Transaction_Value_Date")), "yyyyMMdd");
        }
        TransactionsNachaHeader trxHeader = new TransactionsNachaHeader();
        trxHeader.setSettValDt(settValDt);
        trxHeader.setAppValDt(systemManager.getSystemSecurity().getApplValDt());

        return trxHeader;
    }

    private void formatList(List<TransactionsNachaHeader> trxList) {

        trxList.forEach(trxHeader -> {
            Date tmpDate = DateTime.parseDate(trxHeader.getSettValDt(), "yyyyMMdd");
            trxHeader.setSettValDt(DateTime.formatDate(tmpDate, "dd/MM/yyyy"));
            StringBuilder sb = new StringBuilder(trxHeader.getValTm());
            sb.insert(2, ':');
            trxHeader.setValTm(sb.toString());

        });
    }

}
