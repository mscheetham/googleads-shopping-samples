package shopping.content.v2.samples.orders;

import com.google.api.services.content.ShoppingContent;
import com.google.api.services.content.model.Order;
import com.google.api.services.content.model.OrderLineItem;
import com.google.api.services.content.model.OrderShipmentLineItemShipment;
import com.google.api.services.content.model.OrdersAcknowledgeRequest;
import com.google.api.services.content.model.OrdersCancelLineItemRequest;
import com.google.api.services.content.model.OrdersCreateTestOrderRequest;
import com.google.api.services.content.model.OrdersListResponse;
import com.google.api.services.content.model.OrdersReturnLineItemRequest;
import com.google.api.services.content.model.OrdersShipLineItemsRequest;
import com.google.api.services.content.model.OrdersUpdateMerchantOrderIdRequest;
import com.google.api.services.content.model.OrdersUpdateShipmentRequest;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Random;
import shopping.content.v2.samples.ContentSample;

/**
 * Sample that runs through an entire test order workflow. We run this sample on the sandbox
 * API endpoint, so that we have access to test order creation and don't accidentally mutate real
 * orders.
 */
public class OrdersWorkflow extends ContentSample {
  private int nonce = 0;
  private final Random random = new Random();

  private OrdersWorkflow(String[] args) throws IOException {
    super(args);
  }

  @Override
  public void execute() throws IOException {
    // Create a new test order using the template1 template. Normally orders would be
    // automatically populated by Google in the non-sandbox version, and we'd skip
    // to finding out what orders are currently waiting for us.
    System.out.print("Creating test order... ");
    String orderId = sandbox.orders().createtestorder(config.getMerchantId(),
        new OrdersCreateTestOrderRequest()
            .setTemplateName("template1"))
        .execute()
        .getOrderId();
    System.out.println("done.");
    System.out.printf("Order \"%s\" created.%n", orderId);
    System.out.println();

    // List the unacknowledged orders.
    System.out.printf("Listing unacknowledged orders for merchant %d:%n", config.getMerchantId());
    ShoppingContent.Orders.List listCall = sandbox.orders().list(config.getMerchantId())
        .setAcknowledged(false);
    do {
      OrdersListResponse page = listCall.execute();
      for (Order product : page.getResources()) {
        OrdersUtils.printOrder(product);
      }
      if (page.getNextPageToken() == null) {
        break;
      }
      listCall.setPageToken(page.getNextPageToken());
    } while (true);
    System.out.println();

    // Acknowledge the newly received order. (Normally we'd do this for the orders returned by the
    // list call, but here, we'll just use the one we got from creating the test order.)
    System.out.printf("Acknowledging order \"%s\"... ", orderId);
    System.out.printf("done with status \"%s\".%n",
        sandbox.orders().acknowledge(config.getMerchantId(), orderId,
            new OrdersAcknowledgeRequest()
                .setOperationId(newOperationId()))
            .execute()
            .getExecutionStatus());
    System.out.println();

    // Set the new order's merchant order ID.
    String merchantOrderId = "test order " + String.valueOf(random.nextLong());
    System.out.printf("Updating merchant order ID to \"%s\"... ", merchantOrderId);
    System.out.printf("done with status \"%s\".%n",
        sandbox.orders().updatemerchantorderid(config.getMerchantId(), orderId,
            new OrdersUpdateMerchantOrderIdRequest()
                .setMerchantOrderId(merchantOrderId)
                .setOperationId(newOperationId()))
            .execute()
            .getExecutionStatus());
    System.out.println();

    Order currentOrder = getOrder(orderId);
    OrdersUtils.printOrder(currentOrder);
    System.out.println();

    // Oops, not enough stock for all the Chromecasts, so we cancel one of them.
    System.out.print("Canceling one Chromecast order... ");
    System.out.printf("done with status \"%s\".%n",
        sandbox.orders().cancellineitem(config.getMerchantId(), orderId,
            new OrdersCancelLineItemRequest()
                .setOperationId(newOperationId())
                .setLineItemId(currentOrder.getLineItems().get(0).getId())
                .setQuantity(1L)
                .setReason("noInventory")
                .setReasonText("Ran out of inventory while fulfilling request."))
            .execute()
            .getExecutionStatus());
    System.out.println();


    currentOrder = getOrder(orderId);
    OrdersUtils.printOrder(currentOrder);
    System.out.println();

    // Advance the test order to the shippable state.  Normally this would be done by
    // Google when an order is no longer cancelable by the customer, but here we need
    // to do it ourselves.
    System.out.print("Advancing test order... ");
    sandbox.orders().advancetestorder(config.getMerchantId(), orderId)
        .execute();
    System.out.println("done.");
    System.out.println();

    currentOrder = getOrder(orderId);
    OrdersUtils.printOrder(currentOrder);
    System.out.println();

    // To simulate partial fulfillment, we'll pick the first line item from our test order
    // and ship the amount that's still pending.
    System.out.print("Notifying Google about shipment of first line item... ");
    OrderLineItem item1 = currentOrder.getLineItems().get(0);
    // Storing this in a variable so we can access the shipment/tracking IDs later.
    OrdersShipLineItemsRequest shipReq1 = new OrdersShipLineItemsRequest()
        .setOperationId(newOperationId())
        .setLineItems(ImmutableList.of(
            new OrderShipmentLineItemShipment()
                .setLineItemId(item1.getId())
                .setQuantity(item1.getQuantityPending())))
        .setCarrier(item1.getShippingDetails().getMethod().getCarrier())
        .setShipmentId(String.valueOf(random.nextLong()))
        .setTrackingId(String.valueOf(random.nextLong()));
    System.out.printf("done with status \"%s\".%n",
        sandbox.orders().shiplineitems(config.getMerchantId(), orderId, shipReq1)
            .execute()
            .getExecutionStatus());
    System.out.println();

    currentOrder = getOrder(orderId);
    OrdersUtils.printOrder(currentOrder);
    System.out.println();

    // Now we ship the rest.
    System.out.print("Notifying Google about shipment of second line item... ");
    OrderLineItem item2 = currentOrder.getLineItems().get(1);
    OrdersShipLineItemsRequest shipReq2 = new OrdersShipLineItemsRequest()
        .setOperationId(newOperationId())
        .setLineItems(ImmutableList.of(
            new OrderShipmentLineItemShipment()
                .setLineItemId(item2.getId())
                .setQuantity(item2.getQuantityPending())))
        .setCarrier(item2.getShippingDetails().getMethod().getCarrier())
        .setShipmentId(String.valueOf(random.nextLong()))
        .setTrackingId(String.valueOf(random.nextLong()));
    System.out.printf("done with status \"%s\".%n",
        sandbox.orders().shiplineitems(config.getMerchantId(), orderId, shipReq2)
            .execute()
            .getExecutionStatus());
    System.out.println();

    currentOrder = getOrder(orderId);
    OrdersUtils.printOrder(currentOrder);
    System.out.println();

    // First item arrives to the customer.
    System.out.print("Notifying Google about delivery of first line item... ");
    System.out.printf("done with status \"%s\".%n",
        sandbox.orders().updateshipment(config.getMerchantId(), orderId,
            new OrdersUpdateShipmentRequest()
                .setOperationId(newOperationId())
                .setCarrier(shipReq1.getCarrier())
                .setTrackingId(shipReq1.getTrackingId())
                .setShipmentId(shipReq1.getShipmentId())
                .setStatus("delivered"))
            .execute()
            .getExecutionStatus());
    System.out.println();

    currentOrder = getOrder(orderId);
    OrdersUtils.printOrder(currentOrder);
    System.out.println();

    // Second item arrives.
    System.out.print("Notifying Google about delivery of second line item... ");
    System.out.printf("done with status \"%s\".%n",
        sandbox.orders().updateshipment(config.getMerchantId(), orderId,
            new OrdersUpdateShipmentRequest()
                .setOperationId(newOperationId())
                .setCarrier(shipReq2.getCarrier())
                .setTrackingId(shipReq2.getTrackingId())
                .setShipmentId(shipReq2.getShipmentId())
                .setStatus("delivered"))
            .execute()
            .getExecutionStatus());
    System.out.println();

    currentOrder = getOrder(orderId);
    OrdersUtils.printOrder(currentOrder);
    System.out.println();

    // Customer returns the first delivered item because it's malfunctioning.
    System.out.print("Notifying Google about return of first line item... ");
    System.out.printf("... done with status \"%s\".%n",
        sandbox.orders().returnlineitem(config.getMerchantId(), orderId,
            new OrdersReturnLineItemRequest()
                .setOperationId(newOperationId())
                .setLineItemId(item1.getId())
                .setQuantity(1L)
                .setReason("productArrivedDamaged")
                .setReasonText("Item malfunctioning upon receipt."))
            .execute()
            .getExecutionStatus());
    System.out.println();

    currentOrder = getOrder(orderId);
    OrdersUtils.printOrder(currentOrder);
    System.out.println();
  }

  private String newOperationId() {
    String ret = String.valueOf(nonce);
    nonce++;
    return ret;
  }

  private Order getOrder(String orderId) throws IOException {
    System.out.printf("Retrieving order \"%s\"... ", orderId);
    Order ret = sandbox.orders().get(config.getMerchantId(), orderId).execute();
    System.out.println("done.");
    System.out.println();
    return ret;
  }

  public static void main(String[] args) throws IOException {
    new OrdersWorkflow(args).execute();
  }
}
