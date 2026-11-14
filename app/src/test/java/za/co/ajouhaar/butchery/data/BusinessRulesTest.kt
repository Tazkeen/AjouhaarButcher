package za.co.ajouhaar.butchery.data

import org.junit.Assert.*
import org.junit.Test

class BusinessRulesTest {
    private val products = listOf(Product(1, "Beef Stew", "Beef", 8999, 3.0), Product(2, "Lamb", "Lamb", 12000, 0.0))

    @Test fun moneyParsingUsesCentsAndRejectsFractionsOfCent() {
        assertEquals(12345L, parseMoneyCents("123.45"))
        assertEquals(0L, parseMoneyCents("0.00"))
        assertNull(parseMoneyCents("12.345"))
        assertNull(parseMoneyCents("not money"))
    }

    @Test fun formattingKeepsTwoDecimalPlaces() {
        assertEquals("R89.99", formatMoney(8999))
        assertEquals("R0.05", formatMoney(5))
    }

    @Test fun credentialAndRegistrationValidation() {
        assertNotNull(validateCredentials("bad-address", "password123"))
        assertNotNull(validateCredentials("cashier@example.org", "short"))
        assertNull(validateCredentials("cashier@example.org", "password123"))
        assertEquals("Passwords do not match.", validateRegistration("Cashier Name", "cashier@example.org", "password123", "otherpass"))
        assertNull(validateRegistration("Cashier Name", "cashier@example.org", "password123", "password123"))
    }

    @Test fun saleValidationChecksLinesStockAndPayment() {
        assertNotNull(validateSale(emptyList(), products, 100000))
        assertNotNull(validateSale(listOf(CartLine(1, 0)), products, 100000))
        assertNotNull(validateSale(listOf(CartLine(1, 4)), products, 100000))
        assertNotNull(validateSale(listOf(CartLine(9, 1)), products, 100000))
        assertNotNull(validateSale(listOf(CartLine(1, 1)), products, 8998))
        assertNull(validateSale(listOf(CartLine(1, 1)), products, 8999))
    }

    @Test fun completingSaleDeductsStockAndCalculatesChange() {
        val repository = DemoButcheryRepository()
        val before = repository.products().first { it.id == 1 }.stock
        val result = repository.completeSale(listOf(CartLine(1, 2)), 20000, "Cash", "Test Cashier")
        assertTrue(result.isSuccess)
        val sale = result.getOrThrow()
        assertEquals(17998L, sale.totalCents)
        assertEquals(2002L, sale.changeCents)
        assertEquals(before - 2, repository.products().first { it.id == 1 }.stock, 0.0)
        assertEquals(sale.number, repository.transactions().first().number)
    }

    @Test fun insufficientStockDoesNotCreateTransactionOrChangeInventory() {
        val repository = DemoButcheryRepository()
        val before = repository.products().first { it.id == 1 }.stock
        val result = repository.completeSale(listOf(CartLine(1, before.toInt() + 1)), 999999, "Cash", "Test")
        assertTrue(result.isFailure)
        assertEquals(before, repository.products().first { it.id == 1 }.stock, 0.0)
        assertTrue(repository.transactions().isEmpty())
    }

    @Test fun productAndStockChangesRejectInvalidValues() {
        val repository = DemoButcheryRepository()
        val originalCount = repository.products().size
        assertTrue(repository.addProduct("", "Beef", 5000, 4.0).isFailure)
        assertTrue(repository.addProduct("New Cut", "Beef", 0, 4.0).isFailure)
        assertEquals(originalCount, repository.products().size)

        val originalStock = repository.products().first { it.id == 1 }.stock
        assertTrue(repository.adjustStock(1, -(originalStock.toInt() + 1)).isFailure)
        assertEquals(originalStock, repository.products().first { it.id == 1 }.stock, 0.0)
        assertTrue(repository.adjustStock(999, 1).isFailure)
    }

    @Test fun managerProvisionedStaffCanLogInAndCanBeRemoved() {
        val repository = DemoButcheryRepository()
        assertTrue(repository.addStaff("Cashier.One", "Cashier One", "demo-pass", "Cashier", "").isSuccess)
        assertEquals("Cashier", repository.authenticate("cashier.one", "demo-pass")?.role)
        assertNull(repository.authenticate("cashier.one", "wrong-pass"))
        assertTrue(repository.addStaff("cashier.one", "Duplicate", "demo-pass", "Cashier", "").isFailure)

        val staff = repository.staffUsers().single()
        assertTrue(repository.removeStaff(staff.uid).isSuccess)
        assertNull(repository.authenticate("cashier.one", "demo-pass"))
    }
}
