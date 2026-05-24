package com.bank.ledger.service;

import com.bank.ledger.domain.Account;
import com.bank.ledger.domain.LedgerEntry;
import com.bank.ledger.repository.AccountRepository;
import com.bank.ledger.repository.LedgerEntryRepository;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatementExportService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a high-quality PDF account statement.
     */
    @Transactional(readOnly = true)
    public byte[] generateStatementPdf(String accountNumber) {
        log.info("Generating PDF statement for account: {}", accountNumber);
        
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account " + accountNumber + " not found."));

        List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(account.getId());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);

        try {
            PdfWriter.getInstance(document, out);
            document.open();

            // Colors for professional appearance
            Color primaryColor = new Color(26, 54, 93); // Sleek Deep Navy Blue
            Color secondaryColor = new Color(74, 85, 104); // Slate Gray
            Color tableHeaderBg = new Color(240, 244, 248); // Ice blue
            Color debitColor = new Color(197, 48, 48); // Subtle Red
            Color creditColor = new Color(39, 103, 73); // Subtle Green

            // Title Header
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, primaryColor);
            Paragraph title = new Paragraph("LEDGERCORE BANK", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(5);
            document.add(title);

            Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, secondaryColor);
            Paragraph subtitle = new Paragraph("Official Real-Time Account Statement", subtitleFont);
            subtitle.setAlignment(Element.ALIGN_CENTER);
            subtitle.setSpacingAfter(20);
            document.add(subtitle);

            // Account Details Card
            Font detailsBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, primaryColor);
            Font detailsRegular = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);

            PdfPTable detailsTable = new PdfPTable(2);
            detailsTable.setWidthPercentage(100);
            detailsTable.setSpacingAfter(25);
            
            // Left Column
            PdfPCell cellLeft = new PdfPCell();
            cellLeft.setBorder(Rectangle.NO_BORDER);
            cellLeft.addElement(new Paragraph("Account Holder: " + 
                (account.getUser() != null ? account.getUser().getFirstName() + " " + account.getUser().getLastName() : "SYSTEM"), detailsRegular));
            cellLeft.addElement(new Paragraph("Account Number: " + account.getAccountNumber(), detailsRegular));
            cellLeft.addElement(new Paragraph("Currency: " + account.getCurrency(), detailsRegular));

            // Right Column
            PdfPCell cellRight = new PdfPCell();
            cellRight.setBorder(Rectangle.NO_BORDER);
            cellRight.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph balancePara = new Paragraph();
            balancePara.add(new Chunk("Current Ledger Balance: ", detailsBold));
            balancePara.add(new Chunk("$" + account.getBalance(), detailsBold));
            cellRight.addElement(balancePara);
            
            Paragraph statusPara = new Paragraph();
            statusPara.add(new Chunk("Status: ", detailsRegular));
            statusPara.add(new Chunk(account.getStatus().name(), account.getStatus() == Account.AccountStatus.ACTIVE ? detailsBold : FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, debitColor)));
            cellRight.addElement(statusPara);

            detailsTable.addCell(cellLeft);
            detailsTable.addCell(cellRight);
            document.add(detailsTable);

            // Statement entries Table
            PdfPTable table = new PdfPTable(6);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{3.2f, 1.8f, 3.2f, 1.5f, 2.0f, 2.3f}); // Relative column widths

            // Table Headers
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, primaryColor);
            String[] headers = {"Date", "TX ID", "Description", "Type", "Amount", "Balance After"};

            for (String header : headers) {
                PdfPCell headerCell = new PdfPCell(new Phrase(header, headerFont));
                headerCell.setBackgroundColor(tableHeaderBg);
                headerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                headerCell.setPadding(6);
                table.addCell(headerCell);
            }

            // Entries rows
            Font rowFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
            Font amountDebitFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, debitColor);
            Font amountCreditFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, creditColor);

            for (LedgerEntry entry : entries) {
                // Date
                PdfPCell dateCell = new PdfPCell(new Phrase(entry.getCreatedAt().format(DATE_FORMATTER), rowFont));
                dateCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                dateCell.setPadding(5);
                table.addCell(dateCell);

                // TX ID
                PdfPCell txCell = new PdfPCell(new Phrase(String.valueOf(entry.getTransaction().getId()), rowFont));
                txCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                txCell.setPadding(5);
                table.addCell(txCell);

                // Description
                PdfPCell descCell = new PdfPCell(new Phrase(entry.getTransaction().getDescription(), rowFont));
                descCell.setPadding(5);
                table.addCell(descCell);

                // Entry Type (DEBIT/CREDIT)
                PdfPCell typeCell = new PdfPCell(new Phrase(entry.getEntryType().name(), rowFont));
                typeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                typeCell.setPadding(5);
                table.addCell(typeCell);

                // Amount
                String prefix = entry.getEntryType() == LedgerEntry.EntryType.DEBIT ? "-" : "+";
                Font fontForAmt = entry.getEntryType() == LedgerEntry.EntryType.DEBIT ? amountDebitFont : amountCreditFont;
                PdfPCell amtCell = new PdfPCell(new Phrase(prefix + "$" + entry.getAmount(), fontForAmt));
                amtCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                amtCell.setPadding(5);
                table.addCell(amtCell);

                // Balance After
                PdfPCell balCell = new PdfPCell(new Phrase("$" + entry.getBalanceAfter(), rowFont));
                balCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                balCell.setPadding(5);
                table.addCell(balCell);
            }

            document.add(table);

            // Footer disclaimer
            Paragraph footer = new Paragraph("\n\nThank you for banking with LedgerCore Bank. This is a computer-generated statement and requires no physical signature. Registered audit logs confirm the immutable validity of all listed bookings.", 
                FontFactory.getFont(FontFactory.HELVETICA, 8, Color.LIGHT_GRAY));
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
        } catch (DocumentException e) {
            log.error("Error creating OpenPDF statement for account {}", accountNumber, e);
            throw new RuntimeException("Could not generate PDF statement.", e);
        }

        return out.toByteArray();
    }
}
