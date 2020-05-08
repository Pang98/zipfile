 public byte[] zipFiles(String stpMsgId, UserSession userSession, HttpServletResponse response) throws Exception {

        byte[] buffer = new byte[1024];
        Map<String, Object> params = new HashMap<String, Object>();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int maxLen = 0;
        
        String printedTime = DateTime.formatDate(DateTime.parseDate(DateTime.getCurrentTimestamp(), "yyyy-MM-dd HH:mm:ss"), "dd-MM-yyyy HH:mm:ss");
        String userDir = this.getClass().getResource("").getPath();
        String jasperFilePath = "";
        String messageType = "";
        
        if (userDir.contains("build")) {
            jasperFilePath = userDir.substring(0, userDir.indexOf("build")) + "/web/WEB-INF/jrxml/";
        } else {
            jasperFilePath = System.getProperty("user.dir") + gfJrxmlPath;
        }

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
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
                //generate jasper report code here
                if(stpMsgIdList.get(j).charAt(0) == 'I'){
                    messageType = "Transaction";
                    maxLen = PDF_LR_MAX_LEN_TRANSACTION;
                    
                }else{
                    messageType = "Summary";
                    maxLen = PDF_LR_MAX_LEN_SUMMARY;
                }
                
                String userIdDateTime = alignmentLeftAndRight("User ID : " + userSession.getUsrId(), "Printed Time : " + printedTime, maxLen);
                String CenterMsgId = alignmentToCenter("Message ID - " + stpMsgIdList.get(j), maxLen);
                params.put("userIdAndDateTime", userIdDateTime);

                String ReportTitle = alignmentToCenter("Nacha File Transaction", maxLen);
                params.put("userIdAndDateTime", userIdDateTime);
                params.put("rptTitle", ReportTitle);
                params.put("stpMsgId", CenterMsgId);
                                                    
                String MsgResponse = getNachaViewResponse(stpMsgIdList.get(j), messageType);
                params.put("MessageResponse", MsgResponse);

                String jasperPath = "";
                if(messageType.equals("Summary")){
                    jasperPath = jasperFilePath + "NachaSummaryMessage_PDF.jasper";
                }else{
                    jasperPath = jasperFilePath + "NachaTransactionMessage_PDF.jasper";
                }
                             
                JasperPrint jasperPrint = JasperFillManager.fillReport(jasperPath, params);

                JRAbstractExporter exporterPDF = new JRPdfExporter();
                exporterPDF.setParameter(JRExporterParameter.JASPER_PRINT, jasperPrint);
                exporterPDF.setParameter(JRExporterParameter.OUTPUT_STREAM, baos);
                response.setHeader("Content-Disposition", "attachment; filename=" + stpMsgIdList.get(j) + "_" + printedTime.replaceAll(":", "").replaceAll(" ", "_") + ".pdf");
                response.setContentType("application/pdf");
                exporterPDF.exportReport();
               
                
                fis = new ByteArrayInputStream(baos.toByteArray()); //baos from jasperreport
                String entryname = stpMsgIdList.get(j) + "_" + messageType + "Message_" + printedTime.replaceAll(":", "").replaceAll(" ", "_") + ".pdf";

                // begin writing a new ZIP entry, positions the stream to the start of the entry data
                zos.putNextEntry(new ZipEntry(entryname));

                int length;
                while ((length = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, length);
                }
                zos.closeEntry();
                fis.close();
            }

        } catch (ZipException ex) {
            Log.exception("NachaFileSummaryAndTransactionController", ex);
            baos.reset();
            return baos.toByteArray();
        }
        return baos.toByteArray();
    }

