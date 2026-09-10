package edu.cit.caaway.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

interface InventoryRepository extends JpaRepository<InventoryItem, String> {
}