package com.fixflow.database;

import com.fixflow.model.Customer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access operations for Customer records.
 * Implements Section 7.1 of the FixFlow Project Specification.
 */
public class CustomerDB {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Inserts a new customer record into the database.
     *
     * @param customer customer model containing details
     * @return generated customer_id
     * @throws SQLException if a database error occurs
     */
    public static int createCustomer(Customer customer) throws SQLException {
        String sql = "INSERT INTO customers (full_name, phone, email, address, created_at) VALUES (?, ?, ?, ?, ?)";
        String now = LocalDateTime.now().format(DATE_FORMATTER);

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, customer.getFullName().trim());
            ps.setString(2, customer.getPhone().trim());
            ps.setString(3, customer.getEmail() != null && !customer.getEmail().trim().isEmpty() ? customer.getEmail().trim() : null);
            ps.setString(4, customer.getAddress() != null && !customer.getAddress().trim().isEmpty() ? customer.getAddress().trim() : null);
            ps.setString(5, now);

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    customer.setCustomerId(id);
                    customer.setCreatedAt(now);
                    return id;
                }
            }
        }
        throw new SQLException("Failed to retrieve generated customer ID.");
    }

    /**
     * Updates an existing customer's contact details.
     *
     * @param customer customer with updated fields
     * @return true if updated, false if not found
     * @throws SQLException if a database error occurs
     */
    public static boolean updateCustomer(Customer customer) throws SQLException {
        String sql = "UPDATE customers SET full_name = ?, phone = ?, email = ?, address = ? WHERE customer_id = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customer.getFullName().trim());
            ps.setString(2, customer.getPhone().trim());
            ps.setString(3, customer.getEmail() != null && !customer.getEmail().trim().isEmpty() ? customer.getEmail().trim() : null);
            ps.setString(4, customer.getAddress() != null && !customer.getAddress().trim().isEmpty() ? customer.getAddress().trim() : null);
            ps.setInt(5, customer.getCustomerId());

            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Deletes a customer record.
     * Protects dependent financial and repair history records in accordance with Section 7.10.
     *
     * @param customerId ID of the customer to delete
     * @return true if deleted
     * @throws SQLException if deletion is blocked due to active repairs or foreign keys
     */
    public static boolean deleteCustomer(int customerId) throws SQLException {
        // Enforce business rule: Protect historical repair records
        if (hasRepairHistory(customerId)) {
            throw new SQLException("Cannot delete Customer #" + customerId + ": Associated repair job history exists. " +
                    "Financial and repair records must be preserved.");
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Delete associated device if it has no repairs
                try (PreparedStatement psDev = conn.prepareStatement("DELETE FROM devices WHERE customer_id = ?")) {
                    psDev.setInt(1, customerId);
                    psDev.executeUpdate();
                }

                // Delete customer
                boolean deleted;
                try (PreparedStatement psCust = conn.prepareStatement("DELETE FROM customers WHERE customer_id = ?")) {
                    psCust.setInt(1, customerId);
                    deleted = psCust.executeUpdate() > 0;
                }

                conn.commit();
                return deleted;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Checks if a customer has any associated repair job records.
     *
     * @param customerId customer ID
     * @return true if repair records exist, false otherwise
     * @throws SQLException if a database query fails
     */
    public static boolean hasRepairHistory(int customerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM repair_jobs WHERE customer_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    /**
     * Retrieves a customer by their ID.
     *
     * @param customerId customer ID
     * @return {@link Customer} object, or null if not found
     * @throws SQLException if a database error occurs
     */
    public static Customer getCustomerById(int customerId) throws SQLException {
        String sql = "SELECT customer_id, full_name, phone, email, address, created_at FROM customers WHERE customer_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToCustomer(rs);
                }
            }
        }
        return null;
    }


    public static List<Customer> getAllCustomers() throws SQLException {
        List<Customer> list = new ArrayList<>();
        String sql = "SELECT customer_id, full_name, phone, email, address, created_at FROM customers ORDER BY customer_id DESC";
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapResultSetToCustomer(rs));
            }
        }
        return list;
    }

    /**
     * Searches customers by name, phone number, or exact ID.
     *
     * @param query search text
     * @return matching customers
     * @throws SQLException if a database error occurs
     */
    public static List<Customer> searchCustomers(String query) throws SQLException {
        if (query == null || query.trim().isEmpty()) {
            return getAllCustomers();
        }

        String trimmed = query.trim();
        List<Customer> list = new ArrayList<>();
        String sql = "SELECT customer_id, full_name, phone, email, address, created_at " +
                     "FROM customers " +
                     "WHERE LOWER(full_name) LIKE ? OR phone LIKE ? OR CAST(customer_id AS TEXT) = ? " +
                     "ORDER BY customer_id DESC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String wildcard = "%" + trimmed.toLowerCase() + "%";
            ps.setString(1, wildcard);
            ps.setString(2, wildcard);
            ps.setString(3, trimmed);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToCustomer(rs));
                }
            }
        }
        return list;
    }

    private static Customer mapResultSetToCustomer(ResultSet rs) throws SQLException {
        int id = rs.getInt("customer_id");
        String name = rs.getString("full_name");
        String phone = rs.getString("phone");
        String email = rs.getString("email");
        String address = rs.getString("address");
        String createdAt = rs.getString("created_at");
        return new Customer(id, name, phone, email, address, createdAt);
    }
}

