package com.univocity.trader.account;

import com.univocity.trader.*;
import com.univocity.trader.candles.*;
import com.univocity.trader.config.*;
import com.univocity.trader.simulation.*;

import java.util.concurrent.*;

public class SimulatedAccountManager extends AccountManager implements SimulatedAccountConfiguration {

	private final TradingFees tradingFees;
	private final SimulatedClientAccount account;

	public SimulatedAccountManager(SimulatedClientAccount account, AccountConfiguration<?> configuration, TradingFees tradingFees) {
		super(account, configuration);
		this.account = account;

		if (tradingFees == null) {
			throw new IllegalConfigurationException("Please configure trading fess");
		}

		this.tradingFees = tradingFees;
	}

	public void subtractFromFreeBalance(String symbol, final double amount) {
		Balance balance = getBalance(symbol);
		balance.setFree(balance.getFree() - amount);
	}

	public void subtractFromLockedBalance(String symbol, final double amount) {
		Balance balance = getBalance(symbol);
		balance.setLocked(balance.getLocked() - amount);
	}

	public void releaseFromLockedBalance(String symbol, final double amount) {
		Balance balance = getBalance(symbol);
		double locked = balance.getLocked();
		if (locked < amount) {
			double toTakeFromFreeBalance = amount - locked;
			locked -= toTakeFromFreeBalance;

			balance.setLocked(0.0);
			balance.setFree(balance.getFree() + locked);
		} else {
			balance.setLocked(locked - amount);
			balance.setFree(balance.getFree() + amount);
		}
	}

	public void subtractFromShortedBalance(String symbol, final double amount) {
		Balance balance = getBalance(symbol);
		balance.setShorted(balance.getShorted() - amount);
	}

	public double getMarginReserve(String fundSymbol, String assetSymbol) {
		return getBalance(fundSymbol).getMarginReserve(assetSymbol);

	}

	public void subtractFromMarginReserveBalance(String fundSymbol, String assetSymbol, final double amount) {
		Balance balance = getBalance(fundSymbol);
		balance.setMarginReserve(assetSymbol, balance.getMarginReserve(assetSymbol) - amount);
	}

	public void subtractFromLockedOrFreeBalance(String funds, double amount) {
		subtractFromLockedOrFreeBalance(funds, null, amount);
	}

	public void subtractFromLockedOrFreeBalance(String funds, String asset, double amount) {
		Balance balance = getBalance(funds);
		double locked = balance.getLocked();

		if (amount < locked) { //got more locked funds
			subtractFromLockedBalance(funds, amount);
		} else { //clear locked funds. Account reserve is greater than locked amount when price jumps a bit
			subtractFromLockedBalance(funds, locked);
			double remainder = amount - locked;
			if (balance.getFree() >= remainder) {
				subtractFromFreeBalance(funds, remainder);
			} else if (asset != null) { //use margin
				subtractFromMarginReserveBalance(funds, asset, remainder);
			} else {
				//will throw exception.
				subtractFromLockedBalance(funds, amount);
			}
		}
	}

	public void addToLockedBalance(String symbol, double amount) {
		Balance balance = getBalance(symbol);
		amount = balance.getLocked() + amount;
		balance.setLocked(amount);
	}

	//TODO: need to implement margin release/call according to price movement.
	public void addToMarginReserveBalance(String fundSymbol, String assetSymbol, double amount) {
		Balance balance = getBalance(fundSymbol);
		amount = balance.getMarginReserve(assetSymbol) + amount;
		balance.setMarginReserve(assetSymbol, amount);
	}


	public void addToFreeBalance(String symbol, double amount) {
		Balance balance = getBalance(symbol);
		amount = balance.getFree() + amount;
		balance.setFree(amount);
	}

	public void addToShortedBalance(String symbol, double amount) {
		Balance balance = getBalance(symbol);
		amount = balance.getShorted() + amount;
		balance.setShorted(amount);
	}

	@Override
	public SimulatedAccountManager setAmount(String symbol, double amount) {
		if (configuration.isSymbolSupported(symbol)) {
			balances.put(symbol, new Balance(this, symbol, amount));
			this.balancesArray = null;
			return this;
		}
		throw configuration.reportUnknownSymbol("Can't set funds", symbol);
	}

	public SimulatedAccountConfiguration resetBalances() {
		this.balances.clear();
		this.balancesArray = null;

		fundAllocationCache.clear();
		pendingOrders.clear();

		traders.clear();
		allTradingManagers.clear();
		tradingManagers = null;

		client.reset();
		return this;
	}

	public boolean updateOpenOrders(String symbol, Candle candle) {
		if (this.account.updateOpenOrders(symbol, candle)) {
			orderLock.lock();
			try {
				for (int i = pendingOrders.i - 1; i >= 0; i--) {
					Order order = pendingOrders.elements[i];
					if (symbol.equals(order.getSymbol())) {
						updateOrder(order);
					}
				}
			} finally {
				orderLock.unlock();
			}
			return true;
		}
		return false;
	}

	public void notifySimulationEnd() {
		orderLock.lock();
		try {
			for (int i = pendingOrders.i - 1; i >= 0; i--) {
				pendingOrders.elements[i].cancel();
			}
		} finally {
			orderLock.unlock();
		}
		for (TradingManager t : this.getAllTradingManagers()) {
			updateOpenOrders(t.getSymbol(), t.getLatestCandle());
		}

		balances.clear();
		pendingOrders.clear();
	}

	public AccountManager lockAmount(String symbol, double amount) {
		if (configuration.isSymbolSupported(symbol)) {
			subtractFromFreeBalance(symbol, amount);
			addToLockedBalance(symbol, amount);
			return this;
		}
		throw configuration.reportUnknownSymbol("Can't set funds", symbol);
	}

	public double applyMarginReserve(double amount) {
		return amount * marginReserveFactor;
	}

	@Override
	public TradingFees getTradingFees() {
		return tradingFees;
	}

	@Override
	public ConcurrentHashMap<String, Balance> updateBalances() {
		return balances;
	}

	@Override
	public final Balance getBalance(String symbol) {
		return super.getBalance(symbol);
	}

	@Override
	public ConcurrentHashMap<String, Balance> getBalances() {
		return balances;
	}
}
