package eu.mihosoft.freerouting.autoroute;

import eu.mihosoft.freerouting.board.DrillItem;
import eu.mihosoft.freerouting.board.Item;
import eu.mihosoft.freerouting.datastructures.TimeLimit;
import eu.mihosoft.freerouting.geometry.planar.FloatLine;
import eu.mihosoft.freerouting.geometry.planar.FloatPoint;

import java.util.*;

public class AutorouteItemThread implements Runnable {
    Vector<Collection<Item>> items_to_route;
    BatchAutorouter batch_autorouter;
    boolean with_screen_message;
    int pass_no;
    int threadId;
    int NUM_THREADS;


    public AutorouteItemThread(BatchAutorouter p_batch_autorouter, Vector<Collection<Item>> p_items_to_route,
                               int p_pass_no, boolean p_with_screen_message, int p_threadId, int p_NUM_THREADS){
        this.items_to_route = p_items_to_route;
        this.batch_autorouter = p_batch_autorouter;
        this.with_screen_message = p_with_screen_message;
        this.pass_no = p_pass_no;
        this.threadId = p_threadId;
        this.NUM_THREADS = p_NUM_THREADS;
    }

    public void run(){
        int itemsPerThread = (items_to_route.size() + NUM_THREADS - 1) / NUM_THREADS;
        int startIndex = itemsPerThread * this.threadId;
        int endIndex = Math.min(startIndex + itemsPerThread, items_to_route.size());

        for (int i = startIndex; i < endIndex; i++)
        {
            if(this.items_to_route.get(i) == null) continue;
            for(Item curr_item : this.items_to_route.get(i)){
                if (batch_autorouter.is_interrupted()) {
                    break;
                }
                if (batch_autorouter.thread.is_stop_requested())
                {
                    batch_autorouter.set_is_interrupted(true);
                    break;
                }
                batch_autorouter.routing_board.start_marking_changed_area();
                SortedSet<Item> ripped_item_list = new TreeSet<Item>();
                if (autoroute_item(curr_item, curr_item.get_net_no(0), ripped_item_list, pass_no, batch_autorouter))
                {
                    batch_autorouter.incrementRouted();
                    batch_autorouter.hdlg.repaint();
                }
                else
                {
                    batch_autorouter.incrementNotFound();
                }
                batch_autorouter.decrementItemsToGoCount();
                batch_autorouter.ripped_item_count += ripped_item_list.size();
                if (this.with_screen_message)
                {
                    batch_autorouter.hdlg.screen_messages.set_batch_autoroute_info(batch_autorouter.items_to_go_count, batch_autorouter.routed, batch_autorouter.ripped_item_count, batch_autorouter.not_found);
                }
            }
        }
    }

    private boolean autoroute_item(Item p_item, int p_route_net_no, SortedSet<Item> p_ripped_item_list, int p_ripup_pass_no, BatchAutorouter batch_autorouter)
    {
        try
        {
            boolean contains_plane = false;
            eu.mihosoft.freerouting.rules.Net route_net = batch_autorouter.routing_board.rules.nets.get(p_route_net_no);
            if (route_net != null)
            {
                contains_plane = route_net.contains_plane();
            }
            int curr_via_costs;

            if (contains_plane)
            {
                curr_via_costs = batch_autorouter.hdlg.get_settings().autoroute_settings.get_plane_via_costs();
            }
            else
            {
                curr_via_costs = batch_autorouter.hdlg.get_settings().autoroute_settings.get_via_costs();
            }

            AutorouteControl autoroute_control = new AutorouteControl(batch_autorouter.routing_board, p_route_net_no, batch_autorouter.hdlg.get_settings(), curr_via_costs, batch_autorouter.trace_cost_arr);
            autoroute_control.ripup_allowed = true;
            autoroute_control.ripup_costs = batch_autorouter.start_ripup_costs * p_ripup_pass_no;
            autoroute_control.remove_unconnected_vias = batch_autorouter.remove_unconnected_vias;

            Set<Item> unconnected_set = p_item.get_unconnected_set(p_route_net_no);
            if (unconnected_set.size() == 0)
            {
                return true; // p_item is already routed.

            }
            Set<Item> connected_set = p_item.get_connected_set(p_route_net_no);
            Set<Item> route_start_set;
            Set<Item> route_dest_set;
            if (contains_plane)
            {
                for (Item curr_item : connected_set)
                {
                    if (curr_item instanceof eu.mihosoft.freerouting.board.ConductionArea)
                    {
                        return true; // already connected to plane

                    }
                }
            }
            if (contains_plane)
            {
                route_start_set = connected_set;
                route_dest_set = unconnected_set;
            }
            else
            {
                route_start_set = unconnected_set;
                route_dest_set = connected_set;
            }

            calc_airline(route_start_set, route_dest_set);
            double max_milliseconds = 100000 * Math.pow(2, p_ripup_pass_no - 1);
            max_milliseconds = Math.min(max_milliseconds, Integer.MAX_VALUE);
            TimeLimit time_limit = new TimeLimit((int) max_milliseconds);

            AutorouteEngine.AutorouteResult autoroute_result = findAndRoute(batch_autorouter, p_route_net_no, time_limit, autoroute_control, route_start_set, route_dest_set, p_ripped_item_list);

            return autoroute_result == AutorouteEngine.AutorouteResult.ROUTED || autoroute_result == AutorouteEngine.AutorouteResult.ALREADY_CONNECTED;
        } catch (Exception e)
        {
            return false;
        }
    }

    private void calc_airline(Collection<Item> p_from_items, Collection<Item> p_to_items)
    {
        FloatPoint from_corner = null;
        FloatPoint to_corner = null;
        double min_distance = Double.MAX_VALUE;
        for (Item curr_from_item : p_from_items)
        {
            if (!(curr_from_item instanceof DrillItem))
            {
                continue;
            }
            FloatPoint curr_from_corner = ((DrillItem) curr_from_item).get_center().to_float();
            for (Item curr_to_item : p_to_items)
            {
                if (!(curr_to_item instanceof DrillItem))
                {
                    continue;
                }
                FloatPoint curr_to_corner = ((DrillItem) curr_to_item).get_center().to_float();
                double curr_distance = curr_from_corner.distance_square(curr_to_corner);
                if (curr_distance < min_distance)
                {
                    min_distance = curr_distance;
                    from_corner = curr_from_corner;
                    to_corner = curr_to_corner;
                }
            }
        }
        batch_autorouter.add_air_line(new FloatLine(from_corner, to_corner));
    }

    synchronized private static AutorouteEngine.AutorouteResult findAndRoute(BatchAutorouter batch_autorouter, int p_route_net_no, TimeLimit time_limit, AutorouteControl autoroute_control, Set<Item> route_start_set,
            Set<Item> route_dest_set, SortedSet<Item> p_ripped_item_list){
        AutorouteEngine autoroute_engine = batch_autorouter.routing_board.init_autoroute(p_route_net_no,
                autoroute_control.trace_clearance_class_no, batch_autorouter.thread, time_limit, batch_autorouter.retain_autoroute_database);
        AutorouteEngine.AutorouteResult autoroute_result =  autoroute_engine.autoroute_connection(route_start_set, route_dest_set, autoroute_control,
                p_ripped_item_list);

        if (autoroute_result == AutorouteEngine.AutorouteResult.ROUTED)
        {
            batch_autorouter.routing_board.opt_changed_area(new int[0], null, batch_autorouter.hdlg.get_settings().get_trace_pull_tight_accuracy(), autoroute_control.trace_costs, batch_autorouter.thread, BatchAutorouter.TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP);
        }

        return autoroute_result;
    }

}
