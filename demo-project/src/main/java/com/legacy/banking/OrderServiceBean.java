package com.legacy.banking;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * EJB Session Bean — gestion des commandes (Java EE 5, 2008).
 * God class : validation + persistance + notification dans la même classe.
 */
public class OrderServiceBean {

    private static final Logger log = Logger.getLogger(OrderServiceBean.class.getName());
    private DataSource dataSource;
    private ClientService clientService;
    private PaymentProcessorBean paymentProcessor;
    private NotificationService notifService;
    private InventoryService inventoryService;

    public Long createOrder(Long clientId, List<OrderItem> items) throws Exception {
        // Validation client
        Client client = clientService.findClientByCode(String.valueOf(clientId));
        if (client == null) throw new ServiceException("Client introuvable: " + clientId);
        if (!"A".equals(client.getStatut())) throw new ServiceException("Client inactif");

        // Calcul total
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            if (item.getQuantite() <= 0) throw new IllegalArgumentException("Quantite invalide");
            if (!inventoryService.isAvailable(item.getProduitCode(), item.getQuantite())) {
                throw new ServiceException("Stock insuffisant: " + item.getProduitCode());
            }
            total = total.add(item.getPrix().multiply(new BigDecimal(item.getQuantite())));
        }

        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            // Insertion commande
            PreparedStatement psCmd = conn.prepareStatement(
                "INSERT INTO T_COMMANDE (CMD_CLIENT_ID, CMD_TOTAL, CMD_DATE, CMD_STATUT) " +
                "VALUES (?, ?, SYSDATE, 'EN_COURS')",
                Statement.RETURN_GENERATED_KEYS
            );
            psCmd.setLong(1, clientId);
            psCmd.setBigDecimal(2, total);
            psCmd.executeUpdate();
            ResultSet keys = psCmd.getGeneratedKeys();
            if (!keys.next()) throw new ServiceException("Echec creation commande");
            Long orderId = keys.getLong(1);
            psCmd.close();

            // Insertion lignes
            for (OrderItem item : items) {
                PreparedStatement psLine = conn.prepareStatement(
                    "INSERT INTO T_LIGNE_CMD (LGN_CMD_ID, LGN_PRODUIT, LGN_QTE, LGN_PRIX) " +
                    "VALUES (?, ?, ?, ?)"
                );
                psLine.setLong(1, orderId);
                psLine.setString(2, item.getProduitCode());
                psLine.setInt(3, item.getQuantite());
                psLine.setBigDecimal(4, item.getPrix());
                psLine.executeUpdate();
                psLine.close();
                inventoryService.reserveStock(item.getProduitCode(), item.getQuantite());
            }

            // Paiement
            PaymentResult result = paymentProcessor.processPayment(clientId, total, "CMD");
            if (!result.isSuccess()) {
                conn.rollback();
                throw new ServiceException("Paiement refuse: " + result.getMessage());
            }

            // Mise à jour statut commande
            PreparedStatement psUpdate = conn.prepareStatement(
                "UPDATE T_COMMANDE SET CMD_STATUT = 'VALIDEE', CMD_TRX_ID = ? WHERE CMD_ID = ?"
            );
            psUpdate.setString(1, result.getTransactionId());
            psUpdate.setLong(2, orderId);
            psUpdate.executeUpdate();
            psUpdate.close();

            conn.commit();
            notifService.sendOrderConfirmation(clientId, orderId, total);
            log.info("Commande creee: " + orderId + " client=" + clientId + " total=" + total);
            return orderId;

        } catch (SQLException e) {
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            throw new ServiceException("Erreur creation commande", e);
        } finally {
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    public List<Order> findOrdersByClient(Long clientId, String statut) throws ServiceException {
        List<Order> orders = new ArrayList<>();
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            String sql = "SELECT CMD_ID, CMD_TOTAL, CMD_DATE, CMD_STATUT FROM T_COMMANDE " +
                         "WHERE CMD_CLIENT_ID = ?";
            if (statut != null) sql += " AND CMD_STATUT = ?";
            sql += " ORDER BY CMD_DATE DESC";

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setLong(1, clientId);
            if (statut != null) ps.setString(2, statut);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                Order o = new Order();
                o.setId(rs.getLong("CMD_ID"));
                o.setTotal(rs.getBigDecimal("CMD_TOTAL"));
                o.setStatut(rs.getString("CMD_STATUT"));
                orders.add(o);
            }
            return orders;
        } catch (SQLException e) {
            throw new ServiceException("Erreur recherche commandes", e);
        } finally {
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }
}
