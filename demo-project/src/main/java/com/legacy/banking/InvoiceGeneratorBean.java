package com.legacy.banking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * EJB Session Bean — génération de factures PDF (Java EE 5, 2011).
 * Génération HTML en string concatenation. Pas de template engine.
 * TVA hardcodée 20%. Pas de support multi-devise.
 */
public class InvoiceGeneratorBean {

    private static final Logger log = Logger.getLogger(InvoiceGeneratorBean.class.getName());
    private static final BigDecimal TVA_RATE = new BigDecimal("0.20");
    private static final String DATE_FORMAT = "dd/MM/yyyy";

    private DataSource dataSource;
    private NotificationService notifService;

    public String generateInvoice(Long orderId) throws ServiceException {
        Connection conn = null;
        try {
            conn = dataSource.getConnection();

            // Récupération commande
            PreparedStatement psCmd = conn.prepareStatement(
                "SELECT c.CMD_ID, c.CMD_TOTAL, c.CMD_DATE, c.CMD_CLIENT_ID, " +
                "       cl.CLI_NOM, cl.CLI_PRENOM, cl.CLI_ADRESSE " +
                "FROM T_COMMANDE c JOIN T_CLIENT cl ON c.CMD_CLIENT_ID = cl.CLI_ID " +
                "WHERE c.CMD_ID = ?"
            );
            psCmd.setLong(1, orderId);
            ResultSet rsCmd = psCmd.executeQuery();

            if (!rsCmd.next()) throw new ServiceException("Commande introuvable: " + orderId);

            BigDecimal totalHT   = rsCmd.getBigDecimal("CMD_TOTAL");
            BigDecimal tva       = totalHT.multiply(TVA_RATE).setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalTTC  = totalHT.add(tva);
            String clientNom     = rsCmd.getString("CLI_NOM") + " " + rsCmd.getString("CLI_PRENOM");
            String clientAdresse = rsCmd.getString("CLI_ADRESSE");
            Date   cmdDate       = rsCmd.getDate("CMD_DATE");
            psCmd.close();

            // Récupération lignes
            PreparedStatement psLines = conn.prepareStatement(
                "SELECT LGN_PRODUIT, LGN_QTE, LGN_PRIX FROM T_LIGNE_CMD WHERE LGN_CMD_ID = ?"
            );
            psLines.setLong(1, orderId);
            ResultSet rsLines = psLines.executeQuery();

            // Génération HTML (string concatenation — 2008 style)
            StringBuilder html = new StringBuilder();
            html.append("<html><body>");
            html.append("<h1>FACTURE N°").append(orderId).append("</h1>");
            html.append("<p>Date: ").append(new SimpleDateFormat(DATE_FORMAT).format(cmdDate)).append("</p>");
            html.append("<p>Client: ").append(clientNom).append("</p>");
            html.append("<p>Adresse: ").append(clientAdresse).append("</p>");
            html.append("<table border='1'>");
            html.append("<tr><th>Produit</th><th>Qte</th><th>Prix HT</th><th>Total HT</th></tr>");

            while (rsLines.next()) {
                String produit = rsLines.getString("LGN_PRODUIT");
                int    qte     = rsLines.getInt("LGN_QTE");
                BigDecimal prix = rsLines.getBigDecimal("LGN_PRIX");
                BigDecimal ligneTotal = prix.multiply(new BigDecimal(qte));
                html.append("<tr><td>").append(produit).append("</td>")
                    .append("<td>").append(qte).append("</td>")
                    .append("<td>").append(prix).append("</td>")
                    .append("<td>").append(ligneTotal).append("</td></tr>");
            }
            psLines.close();

            html.append("</table>");
            html.append("<p>Total HT: ").append(totalHT).append(" EUR</p>");
            html.append("<p>TVA 20%: ").append(tva).append(" EUR</p>");
            html.append("<p><b>Total TTC: ").append(totalTTC).append(" EUR</b></p>");
            html.append("</body></html>");

            // Persistance de la facture
            PreparedStatement psInv = conn.prepareStatement(
                "INSERT INTO T_FACTURE (FAC_CMD_ID, FAC_CONTENU, FAC_DATE, FAC_MONTANT_TTC) " +
                "VALUES (?, ?, SYSDATE, ?)"
            );
            psInv.setLong(1, orderId);
            psInv.setString(2, html.toString());
            psInv.setBigDecimal(3, totalTTC);
            psInv.executeUpdate();
            psInv.close();

            notifService.sendInvoice(rsCmd.getLong("CMD_CLIENT_ID"), orderId, html.toString());
            log.info("Facture generee — commande=" + orderId + " TTC=" + totalTTC);
            return html.toString();

        } catch (SQLException e) {
            throw new ServiceException("Erreur generation facture", e);
        } finally {
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }
}
