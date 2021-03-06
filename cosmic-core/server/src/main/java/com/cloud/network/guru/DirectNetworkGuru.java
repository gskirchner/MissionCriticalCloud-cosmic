package com.cloud.network.guru;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.engine.orchestration.service.NetworkOrchestrationService;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Ipv6AddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Service;
import com.cloud.network.Network.State;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkProfile;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.Mode;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.UserIpv6AddressDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionUtil;
import com.cloud.utils.exception.InvalidParameterValueException;
import com.cloud.vm.Nic;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;

import javax.inject.Inject;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DirectNetworkGuru extends AdapterBase implements NetworkGuru {
    private static final Logger s_logger = LoggerFactory.getLogger(DirectNetworkGuru.class);
    private static final TrafficType[] TrafficTypes = {TrafficType.Guest};
    @Inject
    DataCenterDao _dcDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    NetworkModel _networkModel;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    UserIpv6AddressDao _ipv6Dao;
    @Inject
    Ipv6AddressManager _ipv6Mgr;
    @Inject
    NicSecondaryIpDao _nicSecondaryIpDao;
    @Inject
    NicDao _nicDao;
    @Inject
    IpAddressManager _ipAddrMgr;
    @Inject
    NetworkOfferingServiceMapDao _ntwkOfferingSrvcDao;

    protected DirectNetworkGuru() {
        super();
    }

    @Override
    public Network design(final NetworkOffering offering, final DeploymentPlan plan, final Network userSpecified, final Account owner) {
        final DataCenter dc = _dcDao.findById(plan.getDataCenterId());

        if (!canHandle(offering, dc)) {
            return null;
        }

        State state = State.Allocated;
        if (dc.getNetworkType() == NetworkType.Basic) {
            state = State.Setup;
        }

        final NetworkVO config =
                new NetworkVO(offering.getTrafficType(), Mode.Dhcp, BroadcastDomainType.Vlan, offering.getId(), state, plan.getDataCenterId(),
                        plan.getPhysicalNetworkId(), offering.getRedundantRouter());

        if (userSpecified != null) {
            if ((userSpecified.getCidr() == null && userSpecified.getGateway() != null) || (userSpecified.getCidr() != null && userSpecified.getGateway() == null)) {
                throw new InvalidParameterValueException("CIDR and gateway must be specified together or the CIDR must represents the gateway.");
            }

            if ((userSpecified.getIp6Cidr() == null && userSpecified.getIp6Gateway() != null) ||
                    (userSpecified.getIp6Cidr() != null && userSpecified.getIp6Gateway() == null)) {
                throw new InvalidParameterValueException("CIDRv6 and gatewayv6 must be specified together or the CIDRv6 must represents the gateway.");
            }

            if (userSpecified.getCidr() != null) {
                config.setCidr(userSpecified.getCidr());
                config.setGateway(userSpecified.getGateway());
            }

            if (userSpecified.getIp6Cidr() != null) {
                config.setIp6Cidr(userSpecified.getIp6Cidr());
                config.setIp6Gateway(userSpecified.getIp6Gateway());
            }

            if (userSpecified.getBroadcastUri() != null) {
                config.setBroadcastUri(userSpecified.getBroadcastUri());
                config.setState(State.Setup);
            }

            if (userSpecified.getBroadcastDomainType() != null) {
                config.setBroadcastDomainType(userSpecified.getBroadcastDomainType());
            }
        }

        final boolean isSecurityGroupEnabled = _networkModel.areServicesSupportedByNetworkOffering(offering.getId(), Service.SecurityGroup);
        if (isSecurityGroupEnabled) {
            if (userSpecified.getIp6Cidr() != null) {
                throw new InvalidParameterValueException("Didn't support security group with IPv6");
            }
            config.setName("SecurityGroupEnabledNetwork");
            config.setDisplayText("SecurityGroupEnabledNetwork");
        }

        return config;
    }

    protected boolean canHandle(final NetworkOffering offering, final DataCenter dc) {
        // this guru handles only Guest networks in Advance zone with source nat service disabled
        if (dc.getNetworkType() == NetworkType.Advanced && isMyTrafficType(offering.getTrafficType()) && offering.getGuestType() == GuestType.Shared) {
            return true;
        } else {
            s_logger.trace("We only take care of Guest networks of type " + GuestType.Shared);
            return false;
        }
    }

    @Override
    public Network implement(final Network network, final NetworkOffering offering, final DeployDestination destination, final ReservationContext context)
            throws InsufficientVirtualNetworkCapacityException {
        return network;
    }

    @Override
    public NicProfile allocate(final Network network, NicProfile nic, final VirtualMachineProfile vm) throws InsufficientVirtualNetworkCapacityException,
            InsufficientAddressCapacityException, ConcurrentOperationException {

        final DataCenter dc = _dcDao.findById(network.getDataCenterId());

        if (nic == null) {
            nic = new NicProfile(ReservationStrategy.Create, null, null, null, null);
        } else if (nic.getIPv4Address() == null && nic.getIPv6Address() == null) {
            nic.setReservationStrategy(ReservationStrategy.Start);
        } else {
            nic.setReservationStrategy(ReservationStrategy.Create);
        }

        allocateDirectIp(nic, network, vm, dc, nic.getRequestedIPv4(), nic.getRequestedIPv6());
        nic.setReservationStrategy(ReservationStrategy.Create);

        if (nic.getMacAddress() == null) {
            nic.setMacAddress(_networkModel.getNextAvailableMacAddressInNetwork(network.getId()));
            if (nic.getMacAddress() == null) {
                throw new InsufficientAddressCapacityException("Unable to allocate more mac addresses", Network.class, network.getId());
            }
        }

        return nic;
    }

    @Override
    public void reserve(final NicProfile nic, final Network network, final VirtualMachineProfile vm, final DeployDestination dest, final ReservationContext context)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException, ConcurrentOperationException {
        if (nic.getIPv4Address() == null && nic.getIPv6Address() == null) {
            allocateDirectIp(nic, network, vm, dest.getDataCenter(), null, null);
            nic.setReservationStrategy(ReservationStrategy.Create);
        }
    }

    @Override
    public boolean release(final NicProfile nic, final VirtualMachineProfile vm, final String reservationId) {
        return true;
    }

    @Override
    @DB
    public void deallocate(final Network network, final NicProfile nic, final VirtualMachineProfile vm) {
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Deallocate network: networkId: " + nic.getNetworkId() + ", ip: " + nic.getIPv4Address());
        }

        if (nic.getIPv4Address() != null) {
            final IPAddressVO ip = _ipAddressDao.findByIpAndSourceNetworkId(nic.getNetworkId(), nic.getIPv4Address());
            if (ip != null) {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        // if the ip address a part of placeholder, don't release it
                        final Nic placeholderNic = _networkModel.getPlaceholderNicForRouter(network, null);
                        if (placeholderNic != null && placeholderNic.getIPv4Address().equalsIgnoreCase(ip.getAddress().addr())) {
                            s_logger.debug("Not releasing direct ip " + ip.getId() + " yet as its ip is saved in the placeholder");
                        } else {
                            _ipAddrMgr.markIpAsUnavailable(ip.getId());
                            _ipAddressDao.unassignIpAddress(ip.getId());
                        }

                        //unassign nic secondary ip address
                        s_logger.debug("remove nic " + nic.getId() + " secondary ip ");
                        final List<String> nicSecIps;
                        nicSecIps = _nicSecondaryIpDao.getSecondaryIpAddressesForNic(nic.getId());
                        for (final String secIp : nicSecIps) {
                            final IPAddressVO pubIp = _ipAddressDao.findByIpAndSourceNetworkId(nic.getNetworkId(), secIp);
                            _ipAddrMgr.markIpAsUnavailable(pubIp.getId());
                            _ipAddressDao.unassignIpAddress(pubIp.getId());
                        }
                    }
                });
            }
        }

        if (nic.getIPv6Address() != null) {
            _ipv6Mgr.revokeDirectIpv6Address(nic.getNetworkId(), nic.getIPv6Address());
        }
        nic.deallocate();
    }

    @Override
    public void updateNicProfile(final NicProfile profile, final Network network) {
        final DataCenter dc = _dcDao.findById(network.getDataCenterId());
        if (profile != null) {
            profile.setIPv4Dns1(dc.getDns1());
            profile.setIPv4Dns2(dc.getDns2());
            profile.setIPv6Dns1(dc.getIp6Dns1());
            profile.setIPv6Dns2(dc.getIp6Dns2());
        }
    }

    @Override
    public void shutdown(final NetworkProfile network, final NetworkOffering offering) {
    }

    @Override
    @DB
    public boolean trash(final Network network, final NetworkOffering offering) {
        //Have to remove all placeholder nics
        try {
            final long id = network.getId();
            final List<NicVO> nics = _nicDao.listPlaceholderNicsByNetworkId(id);
            if (nics != null) {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        for (final Nic nic : nics) {
                            if (nic.getIPv4Address() != null) {
                                s_logger.debug("Releasing ip " + nic.getIPv4Address() + " of placeholder nic " + nic);
                                final IPAddressVO ip = _ipAddressDao.findByIpAndSourceNetworkId(nic.getNetworkId(), nic.getIPv4Address());
                                if (ip != null) {
                                    _ipAddrMgr.markIpAsUnavailable(ip.getId());
                                    _ipAddressDao.unassignIpAddress(ip.getId());
                                    s_logger.debug("Removing placeholder nic " + nic);
                                    _nicDao.remove(nic.getId());
                                }
                            }
                        }
                    }
                });
            }
            return true;
        } catch (final Exception e) {
            s_logger.error("trash. Exception:" + e.getMessage());
            throw new CloudRuntimeException("trash. Exception:" + e.getMessage(), e);
        }
    }

    @Override
    public void updateNetworkProfile(final NetworkProfile networkProfile) {
        final DataCenter dc = _dcDao.findById(networkProfile.getDataCenterId());
        networkProfile.setDns1(dc.getDns1());
        networkProfile.setDns2(dc.getDns2());
    }

    @Override
    public TrafficType[] getSupportedTrafficType() {
        return TrafficTypes;
    }

    @Override
    public boolean isMyTrafficType(final TrafficType type) {
        for (final TrafficType t : TrafficTypes) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }

    @DB
    protected void allocateDirectIp(final NicProfile nic, final Network network, final VirtualMachineProfile vm, final DataCenter dc, final String requestedIp4Addr,
                                    final String requestedIp6Addr) throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {

        try {
            Transaction.execute(new TransactionCallbackWithExceptionNoReturn<InsufficientCapacityException>() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) throws InsufficientVirtualNetworkCapacityException,
                        InsufficientAddressCapacityException {
                    if (_networkModel.isSharedNetworkWithoutServices(network.getId())) {
                        _ipAddrMgr.allocateNicValues(nic, dc, vm, network, requestedIp4Addr, requestedIp6Addr);
                    } else {
                        _ipAddrMgr.allocateDirectIp(nic, dc, vm, network, requestedIp4Addr, requestedIp6Addr);
                        //save the placeholder nic if the vm is the Virtual router
                        if (vm.getType() == VirtualMachine.Type.DomainRouter) {
                            final Nic placeholderNic = _networkModel.getPlaceholderNicForRouter(network, null);
                            if (placeholderNic == null) {
                                s_logger.debug("Saving placeholder nic with ip4 address " + nic.getIPv4Address() + " and ipv6 address " + nic.getIPv6Address() +
                                        " for the network " + network);
                                _networkMgr.savePlaceholderNic(network, nic.getIPv4Address(), nic.getIPv6Address(), VirtualMachine.Type.DomainRouter);
                            }
                        }
                    }
                }
            });
        } catch (final InsufficientCapacityException e) {
            ExceptionUtil.rethrow(e, InsufficientVirtualNetworkCapacityException.class);
            ExceptionUtil.rethrow(e, InsufficientAddressCapacityException.class);
            throw new IllegalStateException(e);
        }
    }
}
