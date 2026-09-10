import { useEffect, useState } from 'react'

function App() {
  const [inventory, setInventory] = useState([])
  const [selectedProduct, setSelectedProduct] = useState('')
  const [quantity, setQuantity] = useState(1)
  const [result, setResult] = useState(null)

  const fetchInventory = () => {
    fetch('http://localhost:8080/api/inventory')
      .then((res) => res.json())
      .then((data) => {
        setInventory(data)
        if (data.length > 0 && !selectedProduct) {
          setSelectedProduct(data[0].productId)
        }
      })
      .catch((err) => console.error(err))
  }

  useEffect(() => {
    fetchInventory()
  }, [])

  const handleSubmit = (e) => {
    e.preventDefault(); // <--- THIS LINE IS CRITICAL! Stop the browser page reload

    fetch('http://localhost:8080/api/orders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ productId: selectedProduct, quantity: parseInt(quantity) }),
    })
      .then((res) => res.json())
      .then((data) => {
        setResult(data);
        fetchInventory();
      })
      .catch((err) => console.error('Error placing order:', err));
  };

  return (
    <div style={{ padding: '2rem', fontFamily: 'sans-serif', maxWidth: '500px', margin: 'auto' }}>
      <h2>Modular Monolith Order Placement</h2>

      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
        <div>
          <label>Product: </label>
          <select value={selectedProduct} onChange={(e) => setSelectedProduct(e.target.value)}>
            {inventory.map((item) => (
              <option key={item.productId} value={item.productId}>
                {item.name} (Stock: {item.stock})
              </option>
            ))}
          </select>
        </div>

        <div>
          <label>Quantity: </label>
          <input
            type="number"
            min="1"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
          />
        </div>

        <button type="submit">Place Order</button>
      </form>

      {result && (
        <div
          style={{
            marginTop: '1.5rem',
            padding: '1rem',
            borderRadius: '4px',
            backgroundColor: result.status === 'CONFIRMED' ? '#e6fffa' : '#ffebe9',
            border: `1px solid ${result.status === 'CONFIRMED' ? '#38a169' : '#e53e3e'}`,
          }}
        >
          <h3>Status: {result.status}</h3>
          <p>Reason: {result.reason}</p>
          {result.inventory && (
            <p>
              Updated Stock for {result.inventory.name}: {result.inventory.stock}
            </p>
          )}
        </div>
      )}
    </div>
  )
}

export default App