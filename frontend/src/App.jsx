import { useEffect, useState, useRef } from 'react'
import './App.css'

function App() {
  const [inventory, setInventory] = useState([])
  const [cart, setCart] = useState([])
  const [selectedProduct, setSelectedProduct] = useState('')
  const [quantity, setQuantity] = useState(1)
  const [orders, setOrders] = useState([])
  const [notifications, setNotifications] = useState([])
  const [statusMsg, setStatusMsg] = useState(null)
  const [darkMode, setDarkMode] = useState(false)
  const [showNotifications, setShowNotifications] = useState(false)
  const [unreadCount, setUnreadCount] = useState(0)
  const dropdownRef = useRef(null)

  const refreshData = () => {
    // 1. Fetch Inventory
    fetch('http://localhost:8080/api/inventory')
      .then((res) => {
        if (!res.ok) throw new Error('Failed to fetch inventory')
        return res.json()
      })
      .then((data) => {
        if (Array.isArray(data)) {
          setInventory(data)
          if (data.length > 0 && !selectedProduct) {
            setSelectedProduct(data[0].productId)
          }
        }
      })
      .catch((err) => console.error('Inventory fetch error:', err))

    // 2. Fetch Orders
    fetch('http://localhost:8080/api/orders')
      .then((res) => {
        if (!res.ok) throw new Error('Failed to fetch orders')
        return res.json()
      })
      .then((data) => {
        if (Array.isArray(data)) {
          setOrders(data)
        }
      })
      .catch((err) => console.error('Orders fetch error:', err))

    // 3. Fetch Notifications
    fetch('http://localhost:8080/api/notifications')
      .then((res) => {
        if (!res.ok) throw new Error('Failed to fetch notifications')
        return res.json()
      })
      .then((data) => {
        if (Array.isArray(data)) {
          setNotifications(data)
          setUnreadCount(data.length)
        }
      })
      .catch((err) => console.error('Notifications fetch error:', err))
  }

  useEffect(() => {
    refreshData()
  }, [])

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setShowNotifications(false)
      }
    }

    document.addEventListener('mousedown', handleClickOutside)

    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const addToCart = () => {
    if (!selectedProduct) return

    const item = inventory.find((i) => i.productId === selectedProduct)

    setCart([
      ...cart,
      {
        productId: selectedProduct,
        name: item?.name || selectedProduct,
        quantity: parseInt(quantity),
      },
    ])
  }

  const removeFromCart = (index) => {
    setCart(cart.filter((_, i) => i !== index))
  }

  const handleCheckout = () => {
    if (cart.length === 0) return

    const payload = cart.map(({ productId, quantity }) => ({
      productId,
      quantity,
    }))

    fetch('http://localhost:8080/api/orders', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    })
      .then((res) => {
        if (!res.ok) throw new Error('Order submission failed')
        return res.json()
      })
      .then((data) => {
        setStatusMsg({
          type: 'success',
          text: `Order #${data.id} placed successfully!`,
        })

        setCart([])
        refreshData()
      })
      .catch((err) => {
        setStatusMsg({
          type: 'error',
          text: 'Order execution failed. Check stock levels.',
        })

        console.error(err)
      })
  }

  const handleCancelOrder = (orderId) => {
    fetch(`http://localhost:8080/api/orders/${orderId}/cancel`, {
      method: 'POST',
    })
      .then((res) => {
        if (!res.ok) throw new Error('Failed to cancel order')
        return res.json()
      })
      .then((data) => {
        setStatusMsg({
          type: 'success',
          text: `Order #${data.id || orderId} cancelled and stock updated!`,
        })

        refreshData()
      })
      .catch((err) => {
        setStatusMsg({
          type: 'error',
          text: 'Failed to cancel order.',
        })

        console.error(err)
      })
  }

  const totalCartItems = cart.reduce(
    (total, item) => total + Number(item.quantity || 0),
    0
  )

  const totalProducts = inventory.length

  const lowStockItems = inventory.filter(
    (item) => (item.quantity ?? item.stock ?? 0) < 5
  ).length

  const activeOrders = orders.filter(
    (order) =>
      order.status !== 'CANCELLED' &&
      order.status !== 'COMPLETED'
  ).length

  return (
    <div className={`dashboard-container ${darkMode ? 'dark-mode' : 'light-mode'}`}>

      {/* ================= HEADER ================= */}

      <header className="dashboard-header">

        <div className="brand-area">
          <div className="brand-icon">SC</div>

          <div>
            <div className="brand-label">LAB 2</div>

            <h1 className="brand-title">
              Shop Console
            </h1>

            <p className="brand-subtitle">
              Modular Monolith Dashboard
            </p>
          </div>
        </div>

        <div className="header-actions" ref={dropdownRef} >

        <button
            className="theme-button"
            onClick={() => setDarkMode(!darkMode)}
            title={darkMode ? 'Switch to light mode' : 'Switch to dark mode'}
          >
            {darkMode ? '☀️' : '🌙'}
            <span>{darkMode ? 'Light' : 'Dark'}</span>
          </button>

          <div className="system-status">
            <span className="status-dot"></span>
            API Connected
          </div>

          <button
            className="refresh-button"
            onClick={refreshData}
          >
            <span>↻</span>
            Refresh
          </button>

          <button
            className="bell-button"
            onClick={() =>
              setShowNotifications(!showNotifications)
            }
          >
            🔔

            {unreadCount > 0 && (
              <span className="unread-badge">
                {unreadCount}
              </span>
            )}
          </button>

          {showNotifications && (
            <div className="notification-dropdown">

              <div className="dropdown-header">
                <div>
                  <strong>Notifications</strong>
                  <span className="notification-count">
                    {notifications.length} events
                  </span>
                </div>

                <button
                  className="clear-btn"
                  onClick={() => setUnreadCount(0)}
                >
                  Mark read
                </button>
              </div>

              <div className="notification-list">

                {notifications.length === 0 ? (
                  <div className="empty-notifications">
                    <div className="empty-icon">✓</div>
                    <strong>All caught up</strong>
                    <span>No new notifications</span>
                  </div>
                ) : (
                  notifications.map((n) => (
                    <div
                      key={n.id}
                      className="notification-item"
                    >
                      <div className="notification-icon">
                        •
                      </div>

                      <div className="notification-content">
                        <div className="notification-message">
                          {n.message}
                        </div>

                        <div className="notification-time">
                          {n.createdAt
                            ? new Date(
                                n.createdAt
                              ).toLocaleTimeString()
                            : ''}
                        </div>
                      </div>
                    </div>
                  ))
                )}

              </div>
            </div>
          )}

        </div>
      </header>

      {/* ================= STATUS MESSAGE ================= */}

      {statusMsg && (
        <div
          className={`status-message ${
            statusMsg.type === 'success'
              ? 'status-success'
              : 'status-error'
          }`}
        >
          <div className="status-message-icon">
            {statusMsg.type === 'success' ? '✓' : '!'}
          </div>

          <div>
            <strong>
              {statusMsg.type === 'success'
                ? 'Success'
                : 'Action failed'}
            </strong>

            <span>{statusMsg.text}</span>
          </div>
        </div>
      )}

      {/* ================= OVERVIEW ================= */}

      <section className="overview-grid">

        <div className="overview-card">
          <div className="overview-icon purple">
            📦
          </div>

          <div>
            <span className="overview-label">
              Products
            </span>

            <strong className="overview-value">
              {totalProducts}
            </strong>
          </div>
        </div>

        <div className="overview-card">
          <div className="overview-icon cyan">
            🛒
          </div>

          <div>
            <span className="overview-label">
              Cart Items
            </span>

            <strong className="overview-value">
              {totalCartItems}
            </strong>
          </div>
        </div>

        <div className="overview-card">
          <div className="overview-icon orange">
            ⚡
          </div>

          <div>
            <span className="overview-label">
              Active Orders
            </span>

            <strong className="overview-value">
              {activeOrders}
            </strong>
          </div>
        </div>

        <div className="overview-card">
          <div className="overview-icon red">
            !
          </div>

          <div>
            <span className="overview-label">
              Low Stock
            </span>

            <strong className="overview-value">
              {lowStockItems}
            </strong>
          </div>
        </div>

      </section>

      {/* ================= MAIN GRID ================= */}

      <div className="grid-layout">

        {/* ================= INVENTORY ================= */}

        <div className="card inventory-card">

          <div className="section-heading">
            <div className="section-heading-icon">
              📦
            </div>

            <div>
              <h2 className="card-title">
                Inventory & Purchasing
              </h2>

              <p className="section-description">
                Select products and build a new order.
              </p>
            </div>
          </div>

          <div className="purchase-panel">

            <div className="field-group">
              <label>
                Select Item
              </label>

              <select
                value={selectedProduct}
                onChange={(e) =>
                  setSelectedProduct(e.target.value)
                }
              >
                {inventory.map((item) => (
                  <option
                    key={item.productId}
                    value={item.productId}
                  >
                    {item.name || item.productId} (
                    {item.quantity ?? item.stock ?? 0} left)
                  </option>
                ))}
              </select>
            </div>

            <div className="field-group quantity-field">
              <label>
                Qty
              </label>

              <input
                type="number"
                min="1"
                value={quantity}
                onChange={(e) =>
                  setQuantity(e.target.value)
                }
              />
            </div>

            <button
              className="primary-action"
              onClick={addToCart}
            >
              <span>+</span>
              Add to Cart
            </button>

          </div>

          {/* ================= STOCK ================= */}

          <div className="subsection-header">
            <div>
              <h3>Stock Levels</h3>
              <span>
                Current inventory availability
              </span>
            </div>

            <div className="stock-live">
              <span></span>
              Live
            </div>
          </div>

          <div className="table-wrapper">
            <table className="data-table">

              <thead>
                <tr>
                  <th>Product ID</th>
                  <th>Stock Level</th>
                </tr>
              </thead>

              <tbody>
                {inventory.length === 0 ? (
                  <tr>
                    <td
                      colSpan="2"
                      className="empty-table"
                    >
                      No inventory data available.
                    </td>
                  </tr>
                ) : (
                  inventory.map((item) => {
                    const stock =
                      item.quantity ??
                      item.stock ??
                      0

                    return (
                      <tr key={item.productId}>

                        <td>
                          <div className="product-cell">
                            <div className="product-avatar">
                              {(item.name ||
                                item.productId ||
                                'P')
                                .charAt(0)
                                .toUpperCase()}
                            </div>

                            <div>
                              <strong>
                                {item.name ||
                                  item.productId}
                              </strong>

                              <span>
                                {item.productId}
                              </span>
                            </div>
                          </div>
                        </td>

                        <td>
                          <div className="stock-cell">

                            <span
                              className={`badge ${
                                stock < 5
                                  ? 'badge-rejected'
                                  : 'badge-confirmed'
                              }`}
                            >
                              {stock < 5
                                ? 'Low Stock'
                                : 'In Stock'}
                            </span>

                            <strong>
                              {stock}
                            </strong>

                            <span>units</span>

                          </div>
                        </td>

                      </tr>
                    )
                  })
                )}
              </tbody>

            </table>
          </div>

        </div>

        {/* ================= CART ================= */}

        <div className="card cart-card">

          <div className="section-heading">
            <div className="section-heading-icon cart-icon">
              🛒
            </div>

            <div>
              <h2 className="card-title">
                Active Cart
              </h2>

              <p className="section-description">
                Review items before checkout.
              </p>
            </div>
          </div>

          {cart.length === 0 ? (

            <div className="empty-cart">

              <div className="empty-cart-icon">
                🛒
              </div>

              <strong>
                Your cart is empty
              </strong>

              <span>
                Add an item from inventory to begin.
              </span>

            </div>

          ) : (

            <>

              <div className="cart-summary">
                <span>
                  {totalCartItems} item
                  {totalCartItems !== 1 ? 's' : ''}
                </span>

                <span>
                  Ready for checkout
                </span>
              </div>

              <ul className="cart-list">

                {cart.map((item, idx) => (

                  <li
                    key={idx}
                    className="cart-item"
                  >

                    <div className="cart-product">

                      <div className="cart-product-icon">
                        {idx + 1}
                      </div>

                      <div>
                        <strong>
                          {item.name ||
                            item.productId}
                        </strong>

                        <span>
                          ID: {item.productId}
                        </span>

                        <small>
                          Quantity: {item.quantity}
                        </small>
                      </div>

                    </div>

                    <button
                      className="btn-danger remove-button"
                      onClick={() =>
                        removeFromCart(idx)
                      }
                    >
                      Remove
                    </button>

                  </li>

                ))}

              </ul>

              <button
                className="checkout-button"
                onClick={handleCheckout}
              >
                <span>✓</span>
                Complete Order
              </button>

            </>

          )}

        </div>

      </div>

      {/* ================= ORDERS ================= */}

      <div className="card orders-card">

        <div className="orders-header">

          <div className="section-heading">

            <div className="section-heading-icon order-icon">
              📜
            </div>

            <div>
              <h2 className="card-title">
                Order History
              </h2>

              <p className="section-description">
                Track and manage submitted orders.
              </p>
            </div>

          </div>

          <div className="order-count">
            {orders.length} total
          </div>

        </div>

        <div className="table-wrapper">

          <table className="data-table">

            <thead>
              <tr>
                <th>ID</th>
                <th>Status</th>
                <th>Action</th>
              </tr>
            </thead>

            <tbody>

              {orders.length === 0 ? (

                <tr>
                  <td
                    colSpan="3"
                    className="empty-table"
                  >
                    No orders have been placed yet.
                  </td>
                </tr>

              ) : (

                orders.map((o) => (

                  <tr key={o.id}>

                    <td>
                      <span className="order-id">
                        #{o.id}
                      </span>
                    </td>

                    <td>
                      <span
                        className={`badge badge-${
                          o.status?.toLowerCase() ||
                          'pending'
                        }`}
                      >
                        <span className="badge-dot"></span>
                        {o.status || 'PENDING'}
                      </span>
                    </td>

                    <td>

                      {o.status !== 'CANCELLED' ? (

                        <button
                          className="btn-danger cancel-button"
                          onClick={() =>
                            handleCancelOrder(o.id)
                          }
                        >
                          Cancel Order
                        </button>

                      ) : (

                        <span className="cancelled-label">
                          Cancelled
                        </span>

                      )}

                    </td>

                  </tr>

                ))

              )}

            </tbody>

          </table>

        </div>

      </div>

      <footer className="dashboard-footer">
        <span>Shop Console</span>
        <span>•</span>
        <span>Spring Boot API :8080</span>
      </footer>

    </div>
  )
}

export default App