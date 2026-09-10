# Modular Monolith E-Commerce Store (Order & Inventory)

A modular monolith e-commerce application built with Spring Boot, PostgreSQL (Supabase), and React (Vite).

## Repository Contents
- **Backend Source Code:** Spring Boot application featuring modular packages (`shop` and `inventory`).
- **Frontend Source Code:** React UI located in the `/frontend` directory.
- **SQL Script:** Database initialization script located in `schema.sql` (or `supabase.sql`).

---

## Supabase Database Setup Steps

1. **Create Supabase Project:** Log in to [Supabase](https://supabase.com) and create a new project.
2. **Execute SQL Schema:** Open the **SQL Editor** in Supabase and run the initialization script to set up tables and initial data:
   ```sql
   CREATE TABLE inventory (
       product_id VARCHAR(50) PRIMARY KEY,
       name VARCHAR(100) NOT NULL,
       stock INT NOT NULL
   );

   CREATE TABLE orders (
       id BIGSERIAL PRIMARY KEY,
       product_id VARCHAR(50) NOT NULL,
       quantity INT NOT NULL,
       status VARCHAR(20) NOT NULL,
       reason VARCHAR(255),
       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
   );

   INSERT INTO inventory (product_id, name, stock) VALUES
   ('PROD-001', 'Wireless Mouse', 10),
   ('PROD-002', 'Mechanical Keyboard', 5),
   ('PROD-003', 'USB-C Cable', 0);

3. **Configure Connection:** In src/main/resources/application.properties, update your datasource URL and credentials. Note the addition of prepareThreshold=0 to support Supabase's transaction pooler:
   ```sql
   Properties
    spring.datasource.url=jdbc:postgresql://<your-supabase-host>:6543/postgres?sslmode=require&prepareThreshold=0
    spring.datasource.username=postgres.<your-ref>
    spring.datasource.password=<your-password>

## Network Tab Evidence

### Confirmed Order (HTTP 200 / 201)
![Confirmed Order](./images/confirmed_order.png)

### Rejected Order - Insufficient Stock (HTTP 400 / Business Exception)
![Rejected Order](./images/rejected_order.png)

**Confirmed Order (HTTP 200 / 201)**

**Rejected Order - Insufficient Stock (HTTP 400 / Business Exception)**

1. **In-Process Integration vs. Microservices over a Network**

   Integrating the Order and Inventory modules in-process within a modular monolith offers significant operational simplicity. Because both modules reside in the same runtime memory space, calls between them execute via standard Java method invocations rather than network protocols like HTTP or gRPC.

**What you get for free:**

- **Zero Network Overhead:** Method calls execute in microseconds with no network latency, socket overhead, or payload serialization/deserialization penalties.

- **ACID Transactions:** Order placement and inventory reservation happen inside a single @Transactional database boundary. If writing the order fails, the inventory deduction rolls back atomically without requiring eventual consistency patterns.

- **Operational Simplicity:** Deployment, logging, and monitoring are managed under a single service artifact without needing distributed tracing tools.

**What you would need to add back if split into Microservices:**

- **Resilience Patterns:** Circuit breakers, retries, timeouts, and fallbacks to manage network flakiness.

- **Distributed Transactions:** Saga patterns (orchestrated or choreographed) or transactional outboxes to preserve consistency across separate databases.

- **Service Infrastructure:** API gateways, service registries, and security mechanisms like OAuth/JWT for service-to-service authentication.

2. **Importance of Package-Private Visibility on InventoryServiceImpl**

   Applying package-private visibility to InventoryServiceImpl establishes a strict compile-time boundary between modules. In Java, package-private components cannot be accessed outside their declaring package (edu.cit.caaway.inventory).

**Why it matters & what breaks if public:**

- **Encapsulation Protection:** Making InventoryServiceImpl package-private guarantees that the shop module (edu.cit.caaway.shop) can only interact with inventory operations via the explicit InventoryService interface.

- **Preventing Tight Coupling:** If InventoryServiceImpl were made public, developers could bypass the interface, instantiate concrete classes, or invoke internal package methods directly.

- **Fragile Architecture:** Allowing external modules to depend on implementation details makes future refactoring difficult—changes to the internal logic of the inventory module would break dependent code in other modules across the application.

3. **Extracting Inventory into a Microservice & Code Changes**

**When to Extract:**

- **Independent Scaling:** If inventory lookup traffic drastically exceeds order placement volume (e.g., thousands of reads per second during sale events), scaling the inventory module independently becomes cost-effective.

- **Domain & Organizational Boundaries:** When a dedicated engineering team takes full ownership of inventory domain logic, requiring distinct deployment pipelines and isolated databases.

**Necessary Code Changes:**

- **Network Client Abstraction:** Replace direct method calls to InventoryService inside OrderService with an HTTP REST client (such as Spring's RestClient or OpenFeign) or a gRPC stub.

- **Database Separation:** Split the database schema into two distinct physical databases—one for orders and one for inventory.

- **Data Transfer Objects (DTOs):** Introduce network-safe payload serializations rather than referencing shared internal entity classes directly.

- **Asynchronous Messaging:** Refactor synchronous transaction logic to use asynchronous event streaming (e.g., Apache Kafka or RabbitMQ) to handle stock updates via eventual consistency.