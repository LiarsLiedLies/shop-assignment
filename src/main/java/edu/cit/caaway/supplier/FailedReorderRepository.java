package edu.cit.caaway.supplier;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FailedReorderRepository extends JpaRepository<FailedReorder, String> {}